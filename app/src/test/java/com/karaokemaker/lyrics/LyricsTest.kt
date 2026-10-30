package com.karaokemaker.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test
    fun parsesLrcWithEndMarkers() {
        val lines = Lrc.parse("[ar:Test]\n[00:12.50]Первая строка\n[00:15.00]Вторая\n[00:18.2]\n[00:40.00]После проигрыша")
        assertEquals(3, lines.size)
        assertEquals(12_500L, lines[0].startMs)
        assertEquals(18_200L, lines[1].endMs)
        assertEquals("После проигрыша", lines[2].text)
        assertEquals(lines, Lrc.parse(Lrc.toLrc(lines)))
    }

    @Test
    fun countdownOnlyOnLongBreaks() {
        val lines = Lrc.parse("[00:10.00]a\n[00:12.00]b\n[00:14.00]\n[00:40.00]c")
        val inBreak = LyricsTiming.at(lines, 30_000)
        assertEquals(2, inBreak.focus)
        assertEquals(10_000L, inBreak.countdownMs)
        val singing = LyricsTiming.at(lines, 12_500)
        assertEquals(1, singing.active)
        assertNull(singing.countdownMs)
        assertEquals(10_000L, LyricsTiming.at(lines, 0).countdownMs) // intro countdown
    }

    @Test
    fun alignsKnownLyricsToRecognisedWords() {
        val words = listOf(
            TimedText("Ой", 5_000, 5_300), TimedText("мороз", 5_300, 5_800), TimedText("мороз", 5_800, 6_400),
            TimedText("не", 9_000, 9_200), TimedText("марозь", 9_200, 9_800), TimedText("меня", 9_800, 10_300),
        )
        val out = LineBuilder.align(listOf("Ой, мороз, мороз,", "Не морозь меня", "Не морозь меня, моего коня"), Transcript(words, emptyList()), 60_000)
        assertEquals(5_000L, out[0].startMs)
        assertEquals(9_000L, out[1].startMs)
        assertTrue(out[2].startMs > out[1].startMs)
    }

    @Test
    fun groupsWordsIntoLines() {
        val words = listOf(
            TimedText("Hello", 1000, 1400), TimedText("world", 1400, 1900),
            TimedText("Again", 4000, 4500), TimedText("here", 4500, 5000),
        )
        val lines = LineBuilder.fromTranscript(Transcript(words, emptyList()))
        assertEquals(listOf("Hello world", "Again here"), lines.map { it.text })
    }
}

class SubtitleParserTest {
    @Test
    fun readsSrt() {
        val srt = "1\n00:00:05,120 --> 00:00:07,900\nПервая строка\n\n2\n00:00:08,000 --> 00:00:10,500\nВторая\nстрока\n"
        val tr = SubtitleParser.parse("song.srt", srt)!!
        assertEquals(2, tr.segments.size)
        assertEquals(5_120L, tr.segments[0].startMs)
        assertEquals("Вторая строка", tr.segments[1].text)
        assertEquals(listOf("Первая строка", "Вторая строка"), LineBuilder.fromTranscript(tr).map { it.text })
    }

    @Test
    fun readsWhisperJson() {
        val json = """{"text":"x","segments":[{"start":1.5,"end":3.0,"text":" Hello there","words":[{"word":"Hello","start":1.5,"end":2.0},{"word":"there","start":2.1,"end":3.0}]}]}"""
        val tr = SubtitleParser.parse("result.json", json)!!
        assertEquals(1_500L, tr.segments[0].startMs)
        assertEquals(2, tr.words.size)
    }

    @Test
    fun plainTextIsNotATranscript() {
        assertNull(SubtitleParser.parse("result.txt", "just some words"))
    }
}
