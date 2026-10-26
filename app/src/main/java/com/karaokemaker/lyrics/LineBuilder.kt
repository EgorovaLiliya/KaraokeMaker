package com.karaokemaker.lyrics

import kotlin.math.max
import kotlin.math.min

object LineBuilder {
    /** Whisper tends to invent these on silent or instrumental parts. */
    private val hallucinations = listOf(
        "субтитр", "продолжение следует", "редактор", "спасибо за просмотр", "подписывайтесь",
        "thanks for watching", "thank you for watching", "subscribe", "amara.org",
    )

    fun isHallucination(text: String): Boolean {
        val t = text.lowercase()
        return hallucinations.any { it in t }
    }

    /** Groups recognised words into singable lines. */
    fun fromTranscript(tr: Transcript): List<LyricLine> {
        if (tr.words.isEmpty()) {
            return tr.segments.filter { it.text.isNotBlank() }
                .map { LyricLine(it.startMs, it.endMs, it.text.trim()) }
        }
        val segEnds = tr.segments.map { it.endMs }.toSet()
        val lines = mutableListOf<LyricLine>()
        val cur = mutableListOf<TimedText>()
        fun flush() {
            if (cur.isEmpty()) return
            val text = cur.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim()
            if (text.isNotEmpty()) lines += LyricLine(cur.first().startMs, cur.last().endMs, text)
            cur.clear()
        }
        for (w in tr.words) {
            val word = w.text.trim()
            if (word.isEmpty()) continue
            if (cur.isNotEmpty()) {
                val prev = cur.last()
                val chars = cur.sumOf { it.text.trim().length + 1 }
                val gap = w.startMs - prev.endMs
                val prevPunct = prev.text.trim().lastOrNull()?.let { it in ".,!?;:…" } == true
                val capital = word.first().isUpperCase() && word != "I" && !word.startsWith("I'")
                val breakHere = gap >= 700 ||
                    chars >= 48 ||
                    (prevPunct && chars >= 18) ||
                    (capital && chars >= 18) ||
                    (prev.endMs in segEnds && chars >= 12)
                if (breakHere) flush()
            }
            cur += w.copy(text = word)
        }
        flush()
        return lines
    }

    private fun norm(w: String) = w.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun similar(a: String, b: String): Boolean =
        a == b || (a.length >= 3 && b.length >= 3 && lev(a, b) <= max(1, max(a.length, b.length) / 3))

    /**
     * Puts known lyrics (from LRCLIB or pasted by the user) on the timeline of the recognised words.
     * Uses a word-level edit-distance alignment, so recognition mistakes only blur the timing locally.
     */
    fun align(plain: List<String>, tr: Transcript, durationMs: Long): List<LyricLine> {
        val words = tr.words.ifEmpty {
            // No word timestamps: spread every segment's words over its time span.
            tr.segments.flatMap { seg ->
                val parts = seg.text.split(Regex("\\s+")).filter { it.isNotBlank() }
                val step = if (parts.isEmpty()) 0 else (seg.endMs - seg.startMs) / parts.size
                parts.mapIndexed { i, p -> TimedText(p, seg.startMs + i * step, seg.startMs + (i + 1) * step) }
            }
        }
        val lyricTokens = mutableListOf<Pair<Int, String>>() // line index to normalised word
        plain.forEachIndexed { li, line ->
            line.split(Regex("\\s+")).map(::norm).filter { it.isNotEmpty() }.forEach { lyricTokens += li to it }
        }
        val asr = words.map { norm(it.text) }
        if (lyricTokens.isEmpty() || asr.isEmpty()) return Lrc.distribute(plain, durationMs)

        val n = lyricTokens.size
        val m = asr.size
        val w = m + 1
        val cost = IntArray((n + 1) * w)
        for (i in 0..n) cost[i * w] = i
        for (j in 0..m) cost[j] = j
        for (i in 1..n) for (j in 1..m) {
            val sub = if (similar(lyricTokens[i - 1].second, asr[j - 1])) 0 else 1
            cost[i * w + j] = min(cost[(i - 1) * w + j - 1] + sub, min(cost[(i - 1) * w + j], cost[i * w + j - 1]) + 1)
        }
        // Backtrack, collecting which recognised word each lyric token lines up with.
        val firstWord = IntArray(plain.size) { -1 }
        val lastWord = IntArray(plain.size) { -1 }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val sub = if (similar(lyricTokens[i - 1].second, asr[j - 1])) 0 else 1
            when (cost[i * w + j]) {
                cost[(i - 1) * w + j - 1] + sub -> {
                    val li = lyricTokens[i - 1].first
                    firstWord[li] = j - 1
                    if (lastWord[li] == -1) lastWord[li] = j - 1
                    i--; j--
                }
                cost[(i - 1) * w + j] + 1 -> i--
                else -> j--
            }
        }
        val starts = LongArray(plain.size) { if (firstWord[it] >= 0) words[firstWord[it]].startMs else -1 }
        val ends = LongArray(plain.size) { if (lastWord[it] >= 0) words[lastWord[it]].endMs else -1 }
        // Fill lines that matched nothing by interpolating between their neighbours.
        var k = 0
        while (k < plain.size) {
            if (starts[k] >= 0) { k++; continue }
            var e = k
            while (e < plain.size && starts[e] < 0) e++
            val from = if (k > 0) max(ends[k - 1], starts[k - 1]) else 0L
            val to = if (e < plain.size) starts[e] else minOf(max(durationMs, from + 1000), from + 4000L * (e - k + 1))
            val step = (to - from) / (e - k + 1)
            for (x in k until e) {
                starts[x] = from + step * (x - k + 1)
                ends[x] = -1
            }
            k = e
        }
        val out = mutableListOf<LyricLine>()
        for (x in plain.indices) {
            var s = starts[x]
            if (out.isNotEmpty() && s <= out.last().startMs) s = out.last().startMs + 200
            val e = ends[x].takeIf { it > s }
            out += LyricLine(s, e, plain[x])
        }
        return out
    }
}
