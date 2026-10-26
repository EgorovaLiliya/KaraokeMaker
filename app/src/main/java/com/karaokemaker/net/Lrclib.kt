package com.karaokemaker.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Free synced-lyrics database https://lrclib.net */
object Lrclib {
    private const val BASE = "https://lrclib.net/api"

    data class Result(val synced: String?, val plain: String?, val track: String, val artist: String, val duration: Double)

    fun find(artist: String, title: String, durationSec: Int?): Result? {
        if (title.isBlank()) return null
        if (artist.isNotBlank() && durationSec != null && durationSec > 0) {
            val url = "$BASE/get".toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", artist)
                .addQueryParameter("track_name", title)
                .addQueryParameter("duration", durationSec.toString())
                .build()
            getJson(url.toString())?.let { o -> (o as? JSONObject)?.let(::result) }
                ?.takeIf { it.synced != null }?.let { return it }
        }
        val candidates = mutableListOf<Result>()
        if (artist.isNotBlank()) {
            candidates += search(
                "$BASE/search".toHttpUrl().newBuilder()
                    .addQueryParameter("track_name", title)
                    .addQueryParameter("artist_name", artist).build().toString()
            )
        }
        if (candidates.none { it.synced != null }) {
            candidates += search(
                "$BASE/search".toHttpUrl().newBuilder()
                    .addQueryParameter("q", "$artist $title".trim()).build().toString()
            )
        }
        return candidates
            .filter { it.synced != null || it.plain != null }
            .minByOrNull { r ->
                val durPenalty = if (durationSec != null && durationSec > 0 && r.duration > 0) {
                    abs(r.duration - durationSec).let { d -> if (d > 15) 500 + d else d }
                } else 50.0
                (if (r.synced != null) 0.0 else 1000.0) + durPenalty
            }
    }

    private fun search(url: String): List<Result> {
        val arr = getJson(url) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::result) }
    }

    private fun result(o: JSONObject): Result? {
        if (o.optBoolean("instrumental")) return null
        fun s(k: String) = if (o.isNull(k)) null else o.optString(k).ifBlank { null }
        return Result(s("syncedLyrics"), s("plainLyrics"), o.optString("trackName"), o.optString("artistName"), o.optDouble("duration", 0.0))
    }

    private fun getJson(url: String): Any? {
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return runCatching { org.json.JSONTokener(resp.body?.string().orEmpty()).nextValue() }.getOrNull()
        }
    }
}
