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

    /** Groups recognised words into singable lines, without backing-vocal echoes and recognition loops. */
    fun fromTranscript(tr: Transcript): List<LyricLine> = cleanup(rawLines(tr))

    private fun rawLines(tr: Transcript): List<LyricLine> {
        if (tr.words.isEmpty()) {
            return tr.segments.filter { it.text.isNotBlank() }.flatMap(::splitSegment)
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

    private val lineBoundary = Regex("""(?<=[,;.!?…])\s+(?=\p{Lu})""")

    /**
     * Whisper often puts two sung lines in one segment ("…беды, Не дрогнул…"). Split where a new line starts
     * with a capital letter after punctuation, sharing the segment's time by text length.
     */
    /**
     * Whisper segments often start too early: the first one swallows the whole instrumental intro (0:00–1:02
     * for one line). The end is reliable, so a segment longer than the words can take to sing is cut from the start.
     */
    fun sungStart(startMs: Long, endMs: Long, wordCount: Int): Long {
        val longest = max(10_000L, wordCount * 1200L + 2000L)
        if (endMs - startMs <= longest) return startMs
        return max(startMs, endMs - (wordCount * 900L + 1500L))
    }

    /** Recognition that hardly matches the known lyrics: timing it would only produce nonsense. */
    class PoorAlignment(val matchedPercent: Int) : java.io.IOException(
        "распознанный вокал почти не совпадает с текстом (совпало $matchedPercent% слов), " +
            "поэтому время не расставлено. Расставьте его вручную: ✎ → «Расставить время по ходу песни»"
    )

    /** Whether the recognised text is written in the expected language's alphabet ("" accepts anything). */
    fun matchesLanguage(tr: Transcript, lang: String): Boolean {
        if (lang != "ru" && lang != "en") return true
        val text = if (tr.words.isNotEmpty()) tr.words.joinToString(" ") { it.text } else tr.segments.joinToString(" ") { it.text }
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val cyrillic = letters.count { it in '\u0400'..'\u04FF' }.toDouble() / letters.length
        return if (lang == "ru") cyrillic >= 0.5 else cyrillic < 0.2
    }

    /** Number of words recognised, whether with word timestamps or only per segment. */
    fun wordCount(tr: Transcript): Int =
        if (tr.words.isNotEmpty()) tr.words.size else tr.segments.sumOf { words(it.text).size }

    private fun splitSegment(raw: TimedText): List<LyricLine> {
        val seg = raw.copy(startMs = sungStart(raw.startMs, raw.endMs, words(raw.text).size))
        val text = seg.text.trim()
        val parts = mutableListOf<String>()
        for (p in text.split(lineBoundary)) {
            val part = p.trim()
            if (parts.isNotEmpty() && (part.length < 12 || parts.last().length < 12)) {
                parts[parts.lastIndex] = parts.last() + " " + part
            } else if (part.isNotEmpty()) {
                parts += part
            }
        }
        if (parts.size <= 1) return listOf(LyricLine(seg.startMs, seg.endMs, text))
        val total = parts.sumOf { it.length }.toDouble()
        val span = seg.endMs - seg.startMs
        var start = seg.startMs
        return parts.mapIndexed { i, part ->
            val end = if (i == parts.lastIndex) seg.endMs else start + (span * part.length / total).toLong()
            LyricLine(start, end, part).also { start = end }
        }
    }

    private fun words(text: String) = text.split(Regex("\\s+")).map(::norm).filter { it.isNotEmpty() }

    /**
     * Removes what the lead singer does not sing:
     * - short echoes of the end of the previous line ("…но не упал" → "Не упал"), usually backing vocals
     *   that leaked into the lead-vocal track;
     * - long runs of the same line, a typical Whisper loop on fade-outs (at most two in a row are kept).
     */
    fun cleanup(lines: List<LyricLine>): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        var run = 0
        for (line in lines) {
            val w = words(line.text)
            if (w.isEmpty()) continue
            val prev = out.lastOrNull()?.let { words(it.text) }
            if (prev != null) {
                if (w == prev) {
                    run++
                    if (run >= 2) continue
                    out += line
                    continue
                }
                val echo = w.size <= 3 && w.size < prev.size && prev.takeLast(w.size) == w
                if (echo) continue
            }
            run = 0
            out += line
        }
        return out
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
    fun align(plain: List<String>, tr: Transcript, durationMs: Long): List<LyricLine> =
        alignWithScore(plain, tr, durationMs).first

    /** Like [align], but refuses when too few words of the lyrics were found in the recognition. */
    fun alignChecked(plain: List<String>, tr: Transcript, durationMs: Long): List<LyricLine> {
        val (lines, ratio) = alignWithScore(plain, tr, durationMs)
        if (ratio < 0.25) throw PoorAlignment((ratio * 100).toInt())
        return lines
    }

    private fun alignWithScore(plain: List<String>, tr: Transcript, durationMs: Long): Pair<List<LyricLine>, Double> {
        val words = tr.words.ifEmpty {
            // No word timestamps: spread every segment's words over its time span.
            tr.segments.flatMap { seg ->
                val parts = seg.text.split(Regex("\\s+")).filter { it.isNotBlank() }
                val start = sungStart(seg.startMs, seg.endMs, parts.size)
                val step = if (parts.isEmpty()) 0 else (seg.endMs - start) / parts.size
                parts.mapIndexed { i, p -> TimedText(p, start + i * step, start + (i + 1) * step) }
            }
        }
        val lyricTokens = mutableListOf<Pair<Int, String>>() // line index to normalised word
        plain.forEachIndexed { li, line ->
            line.split(Regex("\\s+")).map(::norm).filter { it.isNotEmpty() }.forEach { lyricTokens += li to it }
        }
        val asr = words.map { norm(it.text) }
        if (lyricTokens.isEmpty() || asr.isEmpty()) return Lrc.distribute(plain, durationMs) to 0.0

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
        var matched = 0
        val firstWord = IntArray(plain.size) { -1 }
        val lastWord = IntArray(plain.size) { -1 }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val sub = if (similar(lyricTokens[i - 1].second, asr[j - 1])) 0 else 1
            // On ties skip trailing lyric words first, so repeated lines (choruses) match their earliest occurrence.
            when (cost[i * w + j]) {
                cost[(i - 1) * w + j] + 1 -> i--
                cost[(i - 1) * w + j - 1] + sub -> {
                    val li = lyricTokens[i - 1].first
                    firstWord[li] = j - 1
                    if (lastWord[li] == -1) lastWord[li] = j - 1
                    if (sub == 0) matched++
                    i--; j--
                }
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
            val e0 = ends[x]
            // A line matched across a long stretch (its first word paired with something far earlier) is
            // anchored to its end instead.
            if (e0 > s) s = sungStart(s, e0, words(plain[x]).size)
            if (out.isNotEmpty() && s <= out.last().startMs) s = out.last().startMs + 200
            val e = ends[x].takeIf { it > s }
            out += LyricLine(s, e, plain[x])
        }
        return out to matched.toDouble() / n
    }
}
