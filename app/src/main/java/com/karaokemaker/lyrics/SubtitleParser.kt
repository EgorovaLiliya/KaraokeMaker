package com.karaokemaker.lyrics

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Reads recognition results returned as files: SRT, WebVTT, LRC, Whisper JSON or plain text. */
object SubtitleParser {
    private val cueTime = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")

    fun parse(fileName: String, text: String): Transcript? {
        val body = text.removePrefix("﻿")
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext == "json" || body.trimStart().startsWith("{") || body.trimStart().startsWith("[") -> json(body)
            cueTime.containsMatchIn(body) -> cues(body)
            Lrc.hasTimeTags(body) -> Lrc.parse(body).let { lines ->
                Transcript(emptyList(), lines.mapIndexed { i, l ->
                    TimedText(l.text, l.startMs, LyricsTiming.estimatedEnd(lines, i))
                })
            }
            else -> null // Plain text has no timing; the caller tries other files.
        }
    }

    private fun ms(h: String, m: String, s: String, f: String): Long {
        val frac = f.padEnd(3, '0').take(3).toLong()
        return (h.ifEmpty { "0" }.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + frac
    }

    private fun cues(body: String): Transcript {
        val segments = mutableListOf<TimedText>()
        var current: Pair<Long, Long>? = null
        val textLines = mutableListOf<String>()
        fun flush() {
            val c = current
            val t = textLines.joinToString(" ").replace(Regex("<[^>]+>"), "").trim()
            if (c != null && t.isNotEmpty() && !LineBuilder.isHallucination(t)) segments += TimedText(t, c.first, c.second)
            current = null
            textLines.clear()
        }
        for (raw in body.lines()) {
            val line = raw.trim()
            val m = cueTime.find(line)
            when {
                m != null -> {
                    flush()
                    val g = m.groupValues
                    current = ms(g[1], g[2], g[3], g[4]) to ms(g[5], g[6], g[7], g[8])
                }
                line.isEmpty() -> flush()
                current != null -> textLines += line
            }
        }
        flush()
        return Transcript(emptyList(), segments)
    }

    private fun json(body: String): Transcript? {
        val root = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return null
        val segs = when (root) {
            is JSONObject -> root.optJSONArray("segments") ?: root.optJSONArray("chunks")
            is JSONArray -> root
            else -> null
        } ?: return null
        val segments = mutableListOf<TimedText>()
        val words = mutableListOf<TimedText>()
        fun time(o: JSONObject, key: String, idx: Int): Long? {
            if (o.has(key)) return (o.optDouble(key) * 1000).toLong()
            val ts = o.optJSONArray("timestamp") ?: return null
            return if (ts.isNull(idx)) null else (ts.optDouble(idx) * 1000).toLong()
        }
        for (i in 0 until segs.length()) {
            val o = segs.optJSONObject(i) ?: continue
            val text = o.optString("text").trim()
            val s = time(o, "start", 0) ?: continue
            val e = time(o, "end", 1) ?: s
            if (text.isEmpty() || LineBuilder.isHallucination(text)) continue
            segments += TimedText(text, s, e)
            o.optJSONArray("words")?.let { wa ->
                for (k in 0 until wa.length()) {
                    val w = wa.optJSONObject(k) ?: continue
                    val wt = (w.optString("word").ifEmpty { w.optString("text") }).trim()
                    if (wt.isNotEmpty() && w.has("start")) {
                        words += TimedText(wt, (w.optDouble("start") * 1000).toLong(), (w.optDouble("end", w.optDouble("start")) * 1000).toLong())
                    }
                }
            }
        }
        return Transcript(words, segments)
    }
}
