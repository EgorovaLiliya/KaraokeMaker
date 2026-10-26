package com.karaokemaker.lyrics

import org.json.JSONArray
import org.json.JSONObject

data class TimedText(val text: String, val startMs: Long, val endMs: Long)

data class Transcript(val words: List<TimedText>, val segments: List<TimedText>) {
    fun toJson(): JSONObject = JSONObject()
        .put("words", arr(words))
        .put("segments", arr(segments))

    companion object {
        private fun arr(list: List<TimedText>) = JSONArray().apply {
            list.forEach { put(JSONObject().put("t", it.text).put("s", it.startMs).put("e", it.endMs)) }
        }

        private fun list(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map {
            val o = a.getJSONObject(it)
            TimedText(o.optString("t"), o.optLong("s"), o.optLong("e"))
        }

        fun fromJson(o: JSONObject) = Transcript(list(o.optJSONArray("words")), list(o.optJSONArray("segments")))
    }
}
