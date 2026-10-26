package com.karaokemaker.lyrics

object LyricsTiming {
    /** An instrumental break at least this long gets a countdown. */
    const val GAP_MS = 8_000L

    data class Info(
        /** Line being sung now, or -1. */
        val active: Int,
        /** Line the view should be centred on. */
        val focus: Int,
        /** Time left until the next line during a long instrumental break. */
        val countdownMs: Long?,
        val gapMs: Long,
    )

    fun estimatedEnd(lines: List<LyricLine>, i: Int): Long {
        val line = lines[i]
        line.endMs?.let { return it }
        val words = line.text.split(Regex("\\s+")).count { it.isNotBlank() }
        val estimate = line.startMs + (1200L + words * 450L).coerceIn(2000L, 7000L)
        val next = lines.getOrNull(i + 1)?.startMs
        return if (next != null) minOf(next, estimate) else estimate
    }

    fun at(lines: List<LyricLine>, pos: Long): Info {
        if (lines.isEmpty()) return Info(-1, -1, null, 0)
        var cur = -1
        for (i in lines.indices) {
            if (lines[i].startMs <= pos) cur = i else break
        }
        val next = cur + 1
        val gapStart = if (cur >= 0) estimatedEnd(lines, cur) else 0L
        if (next < lines.size) {
            val gap = lines[next].startMs - gapStart
            if (gap >= GAP_MS && pos >= gapStart) {
                return Info(-1, next, lines[next].startMs - pos, gap)
            }
        }
        return Info(cur, if (cur >= 0) cur else 0, null, 0)
    }
}
