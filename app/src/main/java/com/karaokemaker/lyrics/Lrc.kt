package com.karaokemaker.lyrics

import kotlin.math.max

object Lrc {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val anyTag = Regex("""^\s*(\[[^\]]*])+""")

    /** Parses LRC. A time tag with no text marks the end of the previous line. */
    fun parse(text: String): List<LyricLine> {
        val raw = mutableListOf<Pair<Long, String>>()
        for (line in text.lines()) {
            var rest = line.trim()
            val times = mutableListOf<Long>()
            while (true) {
                val m = timeTag.find(rest)
                if (m == null || m.range.first != 0) break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                times += min * 60_000 + sec * 1000 + ms
                rest = rest.substring(m.range.last + 1)
            }
            if (times.isEmpty()) continue
            times.forEach { raw += it to rest.trim() }
        }
        raw.sortBy { it.first }
        val out = mutableListOf<LyricLine>()
        for ((t, s) in raw) {
            if (s.isBlank()) {
                val last = out.lastOrNull()
                if (last != null && last.endMs == null) out[out.lastIndex] = last.copy(endMs = t)
            } else {
                out += LyricLine(t, null, s)
            }
        }
        return out
    }

    fun hasTimeTags(text: String) = text.lines().any { timeTag.find(it.trim())?.range?.first == 0 }

    fun format(ms: Long): String {
        val t = max(0L, ms)
        val m = t / 60_000
        val s = (t / 1000) % 60
        val cs = (t % 1000) / 10
        return "%02d:%02d.%02d".format(m, s, cs)
    }

    fun toLrc(lines: List<LyricLine>): String = buildString {
        lines.forEachIndexed { i, line ->
            append('[').append(format(line.startMs)).append(']').append(line.text).append('\n')
            val end = line.endMs
            val next = lines.getOrNull(i + 1)
            if (end != null && (next == null || next.startMs - end > 1500)) {
                append('[').append(format(end)).append("]\n")
            }
        }
    }

    fun stripLineTags(line: String): String = line.replace(anyTag, "").trim()

    /** Plain lyrics: no tags, no empty lines, no [Chorus]-style headers. */
    fun plainLines(text: String): List<String> = text.lines()
        .map { stripLineTags(it) }
        .filter { it.isNotBlank() }

    fun shift(lines: List<LyricLine>, deltaMs: Long) = lines.map {
        it.copy(startMs = max(0L, it.startMs + deltaMs), endMs = it.endMs?.let { e -> max(0L, e + deltaMs) })
    }

    /** Spreads untimed lines evenly over the song, as a starting point for manual sync. */
    fun distribute(plain: List<String>, durationMs: Long): List<LyricLine> {
        if (plain.isEmpty()) return emptyList()
        val start = durationMs / 10
        val step = (durationMs * 85 / 100) / plain.size
        return plain.mapIndexed { i, s -> LyricLine(start + i * step, null, s) }
    }
}
