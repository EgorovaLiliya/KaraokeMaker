package com.karaokemaker.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.karaokemaker.lyrics.LineBuilder
import com.karaokemaker.lyrics.Lrc
import com.karaokemaker.lyrics.LyricLine
import com.karaokemaker.lyrics.SubtitleParser
import com.karaokemaker.lyrics.Transcript
import com.karaokemaker.net.Http
import com.karaokemaker.net.Lrclib
import com.karaokemaker.net.Mvsep
import com.karaokemaker.net.Whisper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Turns an mp3 into a karaoke song:
 * synced lyrics from LRCLIB → lead vocal removal on MVSEP → (if needed) lyrics recognised from the lead vocal.
 */
object Processor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    fun import(context: Context, uri: Uri, displayName: String, title: String, artist: String, language: String) {
        val app = context.applicationContext
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val ext = displayName.substringAfterLast('.', "mp3").lowercase().take(5).ifBlank { "mp3" }
        val original = "original.$ext"
        SongStore.save(
            Song(id = id, title = title, artist = artist, durationMs = 0, originalFile = original, language = language, progress = "Копирую файл…")
        )
        start(id) {
            val target = File(SongStore.dir(id), original)
            app.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Не удалось открыть файл")
            val duration = runCatching {
                val r = MediaMetadataRetriever()
                r.setDataSource(target.path)
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                r.release()
                d
            }.getOrDefault(0L)
            SongStore.update(id) { it.copy(durationMs = duration) }
        }
    }

    fun retry(id: String) = start(id) {}

    fun isRunning(id: String) = jobs[id]?.isActive == true

    private fun start(id: String, prepare: suspend () -> Unit) {
        if (isRunning(id)) return
        jobs[id] = scope.launch {
            try {
                SongStore.update(id) { it.copy(status = SongStatus.PROCESSING, error = null) }
                prepare()
                process(id)
                SongStore.update(id) { it.copy(status = SongStatus.READY, progress = "", error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SongStore.update(id) {
                    it.copy(status = SongStatus.ERROR, progress = "", error = e.message ?: e.javaClass.simpleName)
                }
            } finally {
                jobs.remove(id)
            }
        }
    }

    private fun progress(id: String, text: String) {
        SongStore.update(id) { it.copy(progress = text) }
    }

    private suspend fun process(id: String) {
        var song = SongStore.get(id) ?: return
        var lrclib: Lrclib.Result? = null
        if (song.lyrics.isEmpty()) {
            progress(id, "Ищу текст песни в LRCLIB…")
            lrclib = runCatching { Lrclib.find(song.artist, song.title, (song.durationMs / 1000).toInt()) }.getOrNull()
            val found = lrclib
            val synced = found?.synced?.let(Lrc::parse).orEmpty()
            if (found != null && synced.isNotEmpty()) {
                song = SongStore.update(id) { it.copy(lyrics = synced, lyricsSource = "LRCLIB: ${found.artist} — ${found.track}") }!!
            }
        }
        if (song.karaokeFile == null) {
            separate(id)
            song = SongStore.get(id) ?: return
        }
        if (song.lyrics.isEmpty()) {
            val plain = lrclib?.plain
            val (lines, source) = try {
                lyricsFromVocals(song, plain)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PlainTextResult) {
                untimedLines(plain ?: e.text, song.durationMs) to
                    "Текст есть, но без таймингов — ✎ → «Расставить время по ходу песни»"
            } catch (e: Exception) {
                // The backing track is ready anyway; lyrics can be added in the editor.
                val reason = e.message ?: e.javaClass.simpleName
                if (plain != null) {
                    Lrc.distribute(Lrc.plainLines(plain), song.durationMs) to
                        "Текст из LRCLIB без таймингов (распознать не удалось: $reason). Поправьте время в редакторе."
                } else {
                    emptyList<LyricLine>() to "Текст не распознан: $reason. Добавьте его в редакторе ✎"
                }
            }
            SongStore.update(id) { it.copy(lyrics = lines, lyricsSource = source) }
        }
    }

    private suspend fun separate(id: String) {
        val token = Prefs.mvsepToken
        if (token.isBlank()) throw IOException("Укажите API-токен MVSEP в настройках, чтобы убрать основной вокал")
        val song = SongStore.get(id) ?: return
        val names = runMvsepJob(
            id = id,
            audio = SongStore.file(song, song.originalFile),
            sepType = Prefs.sepType,
            options = mapOf("add_opt1" to Prefs.addOpt1),
            prefix = "stem",
            working = "MVSEP отделяет основной вокал…",
            savedHash = song.mvsepHash,
            saveHash = { h -> SongStore.update(id) { it.copy(mvsepHash = h) } },
        )
        val (karaoke, vocals) = pickStems(names)
        SongStore.update(id) { it.copy(stems = names, karaokeFile = karaoke ?: names.first(), vocalsFile = vocals) }
    }

    /**
     * Uploads [audio] to MVSEP, waits for the job and downloads every output file into the song folder.
     * The job hash is persisted through [saveHash], so a restarted app resumes waiting instead of re-uploading.
     */
    private suspend fun runMvsepJob(
        id: String,
        audio: File,
        sepType: Int,
        options: Map<String, String>,
        prefix: String,
        working: String,
        savedHash: String?,
        saveHash: (String?) -> Unit,
        allowNoFiles: Boolean = false,
        onDone: (Mvsep.Job) -> Unit = {},
    ): List<String> {
        var hash = savedHash
        if (hash == null) {
            progress(id, "Загружаю на MVSEP…")
            hash = Mvsep.createJob(Prefs.mvsepToken, audio, sepType, options)
            saveHash(hash)
        }
        val started = System.currentTimeMillis()
        var job: Mvsep.Job
        while (true) {
            job = try {
                Mvsep.getJob(hash)
            } catch (e: IOException) {
                Mvsep.Job("offline", "", emptyList())
            }
            when (job.status) {
                "done" -> break
                "failed", "error", "not_found" -> {
                    saveHash(null)
                    throw IOException("MVSEP не смог обработать файл: ${job.message.ifBlank { job.status }}")
                }
                "waiting" -> progress(id, "В очереди MVSEP… ${job.message}".trim())
                "processing" -> progress(id, working)
                "distributing", "merging" -> progress(id, "MVSEP: почти готово…")
                "offline" -> progress(id, "Нет связи с MVSEP, повторяю… (проверьте интернет/VPN)")
                else -> progress(id, "Ожидаю MVSEP… ${job.message}".trim())
            }
            if (System.currentTimeMillis() - started > 60 * 60_000L) {
                throw IOException("MVSEP слишком долго не отвечает. Попробуйте позже — нажмите «Повторить».")
            }
            delay(5_000)
        }
        onDone(job)
        if (job.files.isEmpty() && !allowNoFiles) {
            saveHash(null)
            throw IOException("MVSEP вернул пустой результат")
        }
        val names = mutableListOf<String>()
        job.files.forEachIndexed { i, f ->
            progress(id, "Скачиваю результат (${i + 1}/${job.files.size})…")
            val safe = "${prefix}_${i}_" + f.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeLast(80)
            withContext(Dispatchers.IO) { Http.download(f.url, File(SongStore.dir(id), safe)) }
            names += safe
        }
        return names
    }

    /** Guesses which MVSEP output is the backing track and which is the lead vocal from file names. */
    fun pickStems(names: List<String>): Pair<String?, String?> {
        fun karaokeScore(n: String): Int {
            val s = n.lowercase()
            var score = 0
            if ("instrum" in s) score += 4
            if ("karaoke" in s) score += 3
            if ("back" in s) score += 2
            if ("other" in s || "minus" in s || "accomp" in s) score += 1
            if ("lead" in s) score -= 4
            return score
        }
        fun vocalScore(n: String): Int {
            val s = n.lowercase()
            var score = 0
            if ("lead" in s) score += 4
            if ("vocal" in s || "voice" in s) score += 2
            if ("instrum" in s || "karaoke" in s || "back" in s || "other" in s) score -= 5
            return score
        }
        val karaoke = names.maxByOrNull(::karaokeScore)?.takeIf { karaokeScore(it) > 0 }
        val vocals = names.filter { it != karaoke }.maxByOrNull(::vocalScore)?.takeIf { vocalScore(it) > 0 }
        if (karaoke == null && names.size == 2 && vocals != null) return names.first { it != vocals } to vocals
        return karaoke to vocals
    }

    private suspend fun lyricsFromVocals(song: Song, plain: String?): Pair<List<LyricLine>, String> {
        if (!canRecognise()) {
            if (plain != null) {
                return Lrc.distribute(Lrc.plainLines(plain), song.durationMs) to
                    "LRCLIB без таймингов — поправьте время в редакторе"
            }
            return emptyList<LyricLine>() to "Текст не найден. Добавьте его в редакторе ✎"
        }
        progress(song.id, "Распознаю текст по вокалу…")
        val tr = transcript(song)
        return if (plain != null) {
            LineBuilder.alignChecked(Lrc.plainLines(plain), tr, song.durationMs) to "LRCLIB, время выставлено по вокалу"
        } else {
            LineBuilder.fromTranscript(tr) to "Распознано нейросетью — проверьте текст в редакторе"
        }
    }

    private fun canRecognise() =
        if (Prefs.recogniser == Prefs.RECOGNISER_MVSEP) Prefs.mvsepToken.isNotBlank() else Prefs.whisperKey.isNotBlank()

    /**
     * Recognition order (cached in the song folder, so re-aligning edited text is free):
     * 1. MVSEP: the original song with "extract vocals first" — MVSEP isolates all vocals with its best model,
     *    which gives Whisper a cleaner voice than our lead-only karaoke stem.
     * 2. Our lead-vocal stem as is.
     * 3. The original mix as is.
     * Stops at the first result with enough words in the song's language; otherwise keeps the best one.
     */
    private suspend fun transcript(song: Song, refresh: Boolean = false): Transcript {
        val cache = SongStore.file(song, "transcript.json")
        val done = SongStore.file(song, "transcript_v3_done")
        val lang = song.language.ifEmpty { Prefs.language }
        val expected = max(15, (song.durationMs / 12_000).toInt())
        fun score(tr: Transcript) = if (LineBuilder.matchesLanguage(tr, lang)) LineBuilder.wordCount(tr) else 0
        if (!refresh && cache.exists()) {
            val cached = runCatching { Transcript.fromJson(JSONObject(cache.readText())) }.getOrNull()
            // Poor or wrong-language results from older versions are recognised again once.
            if (cached != null && (score(cached) >= expected || done.exists())) return cached
        }
        val original = SongStore.file(song, song.originalFile)
        val vocals = song.vocalsFile?.let { SongStore.file(song, it) }?.takeIf { it.exists() }
        val mvsep = Prefs.recogniser == Prefs.RECOGNISER_MVSEP
        val canExtract = mvsep && whisperOptions(mvsepWhisperAlgorithm(), extract = true, lang = lang).let { it != whisperOptions(mvsepWhisperAlgorithm(), extract = false, lang = lang) }

        val attempts = mutableListOf<Attempt>()
        if (canExtract) {
            attempts += Attempt("MVSEP выделяет вокал из оригинала и распознаёт…") { recogniseAudio(song, original, "extract", true, lang) }
        }
        if (vocals != null) {
            attempts += Attempt("Распознаю выделенный основной вокал…") { recogniseAudio(song, vocals, "vocals", false, lang) }
        }
        attempts += Attempt("Распознаю по оригиналу…") { recogniseAudio(song, original, "original", false, lang) }
        var best: Transcript? = null
        var lastError: Exception? = null
        for (attempt in attempts) {
            progress(song.id, attempt.label)
            val tr = try {
                attempt.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                continue
            }
            if (best == null || score(tr) > score(best)) best = tr
            if (score(tr) >= expected) break
        }
        done.writeText("1")
        val result = best ?: throw (lastError ?: IOException("распознавание не нашло слов"))
        if (LineBuilder.wordCount(result) == 0) throw IOException("распознавание не нашло слов")
        if (score(result) == 0) {
            throw IOException("распознан текст не на том языке (ожидался ${if (lang == "ru") "русский" else "английский"})")
        }
        cache.writeText(result.toJson().toString())
        return result
    }

    private class Attempt(val label: String, val run: suspend () -> Transcript)

    private suspend fun recogniseAudio(song: Song, audio: File, tag: String, extract: Boolean, lang: String): Transcript =
        if (Prefs.recogniser == Prefs.RECOGNISER_MVSEP) {
            if (Prefs.mvsepToken.isBlank()) throw IOException("Укажите API-токен MVSEP в настройках")
            val algo = mvsepWhisperAlgorithm()
            mvsepTranscript(song, audio, tag, algo.id, whisperOptions(algo, extract, lang))
        } else {
            if (Prefs.whisperKey.isBlank()) throw IOException("Укажите ключ распознавания речи в настройках")
            withContext(Dispatchers.IO) {
                Whisper.transcribe(audio, Prefs.whisperUrl, Prefs.whisperKey, Prefs.whisperModel, lang)
            }
        }

    /** Changing the language makes earlier recognition useless. */
    fun setLanguage(songId: String, language: String) {
        val song = SongStore.update(songId) { it.copy(language = language) } ?: return
        SongStore.dir(song.id).listFiles().orEmpty()
            .filter { it.name.startsWith("transcript") || it.name.startsWith("whisper_hash") }
            .forEach { it.delete() }
    }

    /** Whisper large-v3 hosted on MVSEP: same token as separation, reachable without VPN. */
    private suspend fun mvsepTranscript(song: Song, vocals: File, tag: String, algo: Int, options: Map<String, String>): Transcript {
        val hashFile = SongStore.file(song, "whisper_hash_$tag.txt")
        var raw: JSONObject? = null
        val names = runMvsepJob(
            id = song.id,
            audio = vocals,
            sepType = algo,
            options = options,
            prefix = "text_$tag",
            working = "MVSEP распознаёт текст…",
            savedHash = hashFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null },
            saveHash = { h -> if (h == null) hashFile.delete() else hashFile.writeText(h) },
            allowNoFiles = true,
            onDone = { raw = it.raw },
        )
        hashFile.delete()
        // Kept for diagnosis: the exact shape of MVSEP's Whisper answer is not documented.
        raw?.let { SongStore.file(song, "whisper_response_$tag.json").writeText(it.toString(2)) }

        val texts = mutableListOf<Pair<String, String>>() // file name hint to content
        names.forEach { n -> texts += n to SongStore.file(song, n).readText() }
        // The text may also come inside the JSON itself, or as links outside `files`.
        val strings = mutableListOf<String>()
        val nodes = mutableListOf<Any>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> { nodes += v; v.keys().forEach { walk(v.opt(it)) } }
                is JSONArray -> { nodes += v; for (i in 0 until v.length()) walk(v.opt(i)) }
                is String -> strings += v
            }
        }
        walk(raw?.opt("data"))
        val audioExt = setOf("mp3", "wav", "flac", "m4a", "ogg", "aac", "opus")
        strings.filter { it.startsWith("http") }
            .filter { url -> url.substringBefore('?').substringAfterLast('.').lowercase() !in audioExt }
            .distinct().take(5)
            .forEachIndexed { i, url ->
                val name = "text_url_${i}_" + url.substringBefore('?').substringAfterLast('/').takeLast(60)
                    .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
                runCatching {
                    withContext(Dispatchers.IO) { Http.download(url, SongStore.file(song, name)) }
                    texts += name to SongStore.file(song, name).readText()
                }
            }
        strings.filter { !it.startsWith("http") && it.length > 20 }.forEach { texts += "inline.txt" to it }
        nodes.forEach { texts += "inline.json" to it.toString() }

        fun rank(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "json" -> 0; "srt" -> 1; "vtt" -> 2; "lrc" -> 3; "txt" -> 5; else -> 4
        }
        for ((name, content) in texts.sortedBy { rank(it.first) }) {
            val tr = runCatching { SubtitleParser.parse(name, content) }.getOrNull() ?: continue
            if (tr.words.isNotEmpty() || tr.segments.isNotEmpty()) return tr
        }
        // Only plain text: still useful, the user can time it with tap-to-sync.
        val plain = texts.filter { (n, c) ->
            val t = c.trim()
            n.endsWith(".txt") && t.contains(' ') && !t.startsWith("{") && (n != "inline.txt" || t.length > 80)
        }
            .maxByOrNull { it.second.length }?.second
        if (plain != null) throw PlainTextResult(plain.trim())
        throw IOException(
            "MVSEP (модель №$algo) не вернул текст. Ответ: " +
                (raw?.optJSONObject("data")?.toString() ?: raw?.toString()).orEmpty().take(300)
        )
    }

    /** Recognition gave words but no timing. */
    class PlainTextResult(val text: String) : IOException("текст распознан без таймингов")

    /** Splits an untimed recognised blob into singable lines spread over the song. */
    private fun untimedLines(text: String, durationMs: Long): List<LyricLine> {
        val lines = if (text.contains('\n')) {
            Lrc.plainLines(text)
        } else {
            val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
                .mapIndexed { i, w -> com.karaokemaker.lyrics.TimedText(w, i * 100L, i * 100L + 100) }
            LineBuilder.fromTranscript(Transcript(words, emptyList())).map { it.text }
        }
        return Lrc.distribute(lines, durationMs)
    }

    data class WhisperAlgo(val id: Int, val name: String, val fields: List<Mvsep.Field>)

    @Volatile private var whisperAlgo: WhisperAlgo? = null

    /** The Whisper model and its fields from MVSEP's catalogue (read once per app run). */
    fun mvsepWhisperAlgorithm(): WhisperAlgo {
        whisperAlgo?.let { return it }
        val algo = runCatching { Mvsep.algorithms() }.getOrNull()
            ?.filter { "whisper" in it.name.lowercase() }
            ?.maxByOrNull { if ("large" in it.name.lowercase()) 1 else 0 }
            ?: return WhisperAlgo(Prefs.DEFAULT_MVSEP_WHISPER, "Whisper", emptyList())
        return WhisperAlgo(algo.id, algo.name, algo.fields).also { whisperAlgo = it }
    }

    private fun label(o: Mvsep.Option) = o.label.lowercase()

    private fun languageOption(f: Mvsep.Field, lang: String): Mvsep.Option? = f.options.firstOrNull { o ->
        val l = label(o)
        when (lang) {
            "ru" -> "russian" in l || "русск" in l || l.trim() == "ru"
            "en" -> "english" in l || "англ" in l || l.trim() == "en"
            else -> "auto" in l || "detect" in l || "авто" in l
        }
    }

    private fun isLanguageField(f: Mvsep.Field) =
        f.options.any { o -> label(o).let { "russian" in it || "english" in it || "русск" in it || "англ" in it } }

    private fun extractOption(f: Mvsep.Field) = f.options.firstOrNull { o ->
        val l = label(o)
        listOf("extract", "separat", "извлеч", "выдел", "отдел").any { it in l } ||
            (listOf("vocal", "вокал").any { it in l } && listOf("first", "сначала", "before").any { it in l })
    }

    private fun asIsOption(f: Mvsep.Field, extract: Mvsep.Option?) = f.options.filter { it != extract }.firstOrNull { o ->
        listOf("original", "as is", "directly", "without", "оригинал", "без", "как есть").any { it in label(o) }
    } ?: f.options.singleOrNull { it != extract }.takeIf { extract != null && f.options.size == 2 }

    /** Values for the model's fields: language, vocal extraction, then manual choices from Settings on top. */
    fun whisperOptions(algo: WhisperAlgo, extract: Boolean, lang: String): Map<String, String> {
        val overrides = runCatching { JSONObject(Prefs.mvsepWhisperOverrides) }.getOrDefault(JSONObject())
        val out = linkedMapOf<String, String>()
        for (f in algo.fields) {
            if (overrides.has(f.name)) {
                out[f.name] = overrides.getString(f.name)
                continue
            }
            if (isLanguageField(f)) {
                languageOption(f, lang)?.let { out[f.name] = it.key }
                continue
            }
            val ex = extractOption(f) ?: continue
            val chosen = if (extract) ex else asIsOption(f, ex)
            chosen?.let { out[f.name] = it.key }
        }
        return out
    }

    // ---- Used by the lyrics editor ----

    suspend fun findLrclib(artist: String, title: String, durationMs: Long): Lrclib.Result? = withContext(Dispatchers.IO) {
        Lrclib.find(artist, title, (durationMs / 1000).toInt())
    }

    /** Recognises the lead vocal; when [plain] is given, aligns that text instead of using recognised words. */
    suspend fun recognise(songId: String, plain: List<String>?, refresh: Boolean): List<LyricLine> = withContext(Dispatchers.IO) {
        val song = SongStore.get(songId) ?: throw IOException("Песня не найдена")
        try {
            val tr = transcript(song, refresh)
            if (plain.isNullOrEmpty()) LineBuilder.fromTranscript(tr) else LineBuilder.alignChecked(plain, tr, song.durationMs)
        } catch (e: PlainTextResult) {
            if (plain.isNullOrEmpty()) untimedLines(e.text, song.durationMs) else throw e
        }
    }
}
