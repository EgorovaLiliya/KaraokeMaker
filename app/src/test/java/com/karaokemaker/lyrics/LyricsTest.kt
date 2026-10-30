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

class CleanupTest {
    private fun seg(t: String, s: Long, e: Long) = TimedText(t, s, e)

    @Test
    fun dropsEchoesAndLoops() {
        val segments = listOf(
            seg("Я ранен был, но не упал.", 60_000, 64_000),
            seg("Не упал.", 64_640, 66_240),
            seg("Я ранен был, но не упал.", 71_260, 75_900),
            seg("Я как и прежде не боюсь.", 149_000, 154_000),
            seg("Не боюсь.", 155_500, 157_000),
            seg("Не боюсь.", 157_620, 159_000),
            seg("Я капитан своей души.", 187_980, 192_240),
            seg("Я капитан своей души.", 200_780, 204_000),
            seg("Я капитан своей души.", 208_000, 210_000),
            seg("Я капитан своей души.", 211_260, 213_000),
        )
        val lines = LineBuilder.fromTranscript(Transcript(emptyList(), segments)).map { it.text }
        assertEquals(
            listOf(
                "Я ранен был, но не упал.",
                "Я ранен был, но не упал.",
                "Я как и прежде не боюсь.",
                "Я капитан своей души.",
                "Я капитан своей души.",
            ),
            lines,
        )
    }

    @Test
    fun splitsTwoSungLinesInOneSegment() {
        val lines = LineBuilder.fromTranscript(
            Transcript(emptyList(), listOf(seg("Я попал в тиски беды, Не дрогнул и не застонал,", 30_000, 40_000)))
        )
        assertEquals(listOf("Я попал в тиски беды,", "Не дрогнул и не застонал,"), lines.map { it.text })
        assertEquals(30_000L, lines[0].startMs)
        assertTrue(lines[1].startMs in 33_000L..36_000L)
        assertEquals(40_000L, lines[1].endMs)
    }
}

class LongSegmentTest {
    @Test
    fun segmentSwallowingTheIntroIsAnchoredToItsEnd() {
        val tr = Transcript(
            emptyList(),
            listOf(
                TimedText("провожаемый взглядами продавщиц,", 0, 62_500),
                TimedText("сбитый каретой скорой помощи,", 63_000, 66_000),
            ),
        )
        val recognised = LineBuilder.fromTranscript(tr)
        assertTrue(recognised[0].startMs > 58_000)
        val aligned = LineBuilder.align(listOf("провожаемый взглядами продавщиц,", "сбитый каретой скорой помощи,"), tr, 240_000)
        assertTrue(aligned[0].startMs > 58_000)
        assertTrue(aligned[1].startMs in 62_000L..64_000L)
    }
}

class PoorAlignmentTest {
    @Test(expected = LineBuilder.PoorAlignment::class)
    fun refusesToTimeLyricsAgainstGarbage() {
        val tr = Transcript(
            emptyList(),
            listOf(TimedText("you", 180, 240), TimedText("you", 30_000, 30_360), TimedText("you you", 300_100, 302_520)),
        )
        LineBuilder.alignChecked(
            listOf("провожаемый взглядами продавщиц,", "сбитый каретой скорой помощи,", "улыбающийся,"),
            tr,
            300_000,
        )
    }
}

class LanguageTest {
    @Test
    fun detectsWrongLanguage() {
        val english = Transcript(emptyList(), listOf(TimedText("I walk alone in the night", 0, 3000)))
        val russian = Transcript(emptyList(), listOf(TimedText("Я капитан своей души", 0, 3000)))
        assertTrue(!LineBuilder.matchesLanguage(english, "ru"))
        assertTrue(LineBuilder.matchesLanguage(russian, "ru"))
        assertTrue(LineBuilder.matchesLanguage(english, ""))
    }
}
