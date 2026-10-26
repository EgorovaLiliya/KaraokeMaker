package com.karaokemaker.lyrics

import org.json.JSONArray
import org.json.JSONObject

data class LyricLine(val startMs: Long, val endMs: Long? = null, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("s", startMs).put("t", text).apply {
        if (endMs != null) put("e", endMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = LyricLine(
            startMs = o.optLong("s"),
            endMs = if (o.has("e")) o.optLong("e") else null,
            text = o.optString("t"),
        )

        fun listToJson(lines: List<LyricLine>) = JSONArray().apply { lines.forEach { put(it.toJson()) } }

        fun listFromJson(a: JSONArray?): List<LyricLine> =
            if (a == null) emptyList() else (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }
}
