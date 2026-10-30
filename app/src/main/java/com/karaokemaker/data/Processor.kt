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
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns an mp3 into a karaoke song:
 * synced lyrics from LRCLIB → lead vocal removal on MVSEP → (if needed) lyrics recognised from the lead vocal.
 */
object Processor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    fun import(context: Context, uri: Uri, displayName: String, title: String, artist: String) {
        val app = context.applicationContext
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val ext = displayName.substringAfterLast('.', "mp3").lowercase().take(5).ifBlank { "mp3" }
        val original = "original.$ext"
        SongStore.save(Song(id = id, title = title, artist = artist, durationMs = 0, originalFile = original, progress = "Копирую файл…"))
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
            addOpt1 = Prefs.addOpt1,
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
        addOpt1: String?,
        prefix: String,
        working: String,
        savedHash: String?,
        saveHash: (String?) -> Unit,
    ): List<String> {
        var hash = savedHash
        if (hash == null) {
            progress(id, "Загружаю на MVSEP…")
            hash = Mvsep.createJob(Prefs.mvsepToken, audio, sepType, addOpt1)
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
        if (job.files.isEmpty()) {
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
            LineBuilder.align(Lrc.plainLines(plain), tr, song.durationMs) to "LRCLIB, время выставлено по вокалу"
        } else {
            LineBuilder.fromTranscript(tr) to "Распознано нейросетью — проверьте текст в редакторе"
        }
    }

    private fun canRecognise() =
        if (Prefs.recogniser == Prefs.RECOGNISER_MVSEP) Prefs.mvsepToken.isNotBlank() else Prefs.whisperKey.isNotBlank()

    /** Recognition result is cached in the song folder, so re-aligning edited text is free. */
    private suspend fun transcript(song: Song, refresh: Boolean = false): Transcript {
        val cache = SongStore.file(song, "transcript.json")
        if (!refresh && cache.exists()) {
            runCatching { return Transcript.fromJson(JSONObject(cache.readText())) }
        }
        val source = SongStore.file(song, song.vocalsFile ?: song.originalFile)
        val tr = if (Prefs.recogniser == Prefs.RECOGNISER_MVSEP) {
            if (Prefs.mvsepToken.isBlank()) throw IOException("Укажите API-токен MVSEP в настройках")
            mvsepTranscript(song, source)
        } else {
            if (Prefs.whisperKey.isBlank()) throw IOException("Укажите ключ распознавания речи в настройках")
            Whisper.transcribe(source, Prefs.whisperUrl, Prefs.whisperKey, Prefs.whisperModel, Prefs.language)
        }
        if (tr.words.isEmpty() && tr.segments.isEmpty()) throw IOException("распознавание не нашло слов")
        cache.writeText(tr.toJson().toString())
        return tr
    }

    /** Whisper large-v3 hosted on MVSEP: same token as separation, reachable without VPN. */
    private suspend fun mvsepTranscript(song: Song, vocals: File): Transcript {
        val (algo, opt) = mvsepWhisperAlgorithm()
        val hashFile = SongStore.file(song, "whisper_hash.txt")
        val names = runMvsepJob(
            id = song.id,
            audio = vocals,
            sepType = algo,
            addOpt1 = opt,
            prefix = "text",
            working = "MVSEP распознаёт текст…",
            savedHash = hashFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null },
            saveHash = { h -> if (h == null) hashFile.delete() else hashFile.writeText(h) },
        )
        hashFile.delete()
        val files = names.map { SongStore.file(song, it) }
        // Prefer formats with timestamps.
        val ordered = files.sortedBy { f ->
            when (f.extension.lowercase()) {
                "json" -> 0; "srt" -> 1; "vtt" -> 2; "lrc" -> 3; "txt" -> 5; else -> 4
            }
        }
        for (f in ordered) {
            val tr = runCatching { SubtitleParser.parse(f.name, f.readText()) }.getOrNull() ?: continue
            if (tr.words.isNotEmpty() || tr.segments.isNotEmpty()) return tr
        }
        throw IOException("MVSEP вернул файлы без текста: ${names.joinToString()}")
    }

    /** Looks the Whisper model up in MVSEP's catalogue (cached), preferring the "use file as is" option. */
    private fun mvsepWhisperAlgorithm(): Pair<Int, String?> {
        if (Prefs.mvsepWhisperId > 0) return Prefs.mvsepWhisperId to Prefs.mvsepWhisperOpt.ifEmpty { null }
        val algo = runCatching { Mvsep.algorithms() }.getOrNull()
            ?.filter { "whisper" in it.name.lowercase() }
            ?.maxByOrNull { if ("large" in it.name.lowercase()) 1 else 0 }
            ?: return Prefs.DEFAULT_MVSEP_WHISPER to null
        val options = algo.fields.firstOrNull { it.name == "add_opt1" }?.options.orEmpty()
        // We already send the isolated lead vocal, so no extra vocal extraction is needed.
        val asIs = options.firstOrNull { o ->
            val l = o.label.lowercase()
            listOf("original", "as is", "directly", "without", "оригинал", "без").any { it in l }
        }
        Prefs.mvsepWhisperId = algo.id
        Prefs.mvsepWhisperOpt = asIs?.key.orEmpty()
        return algo.id to asIs?.key
    }

    // ---- Used by the lyrics editor ----

    suspend fun findLrclib(artist: String, title: String, durationMs: Long): Lrclib.Result? = withContext(Dispatchers.IO) {
        Lrclib.find(artist, title, (durationMs / 1000).toInt())
    }

    /** Recognises the lead vocal; when [plain] is given, aligns that text instead of using recognised words. */
    suspend fun recognise(songId: String, plain: List<String>?, refresh: Boolean): List<LyricLine> = withContext(Dispatchers.IO) {
        val song = SongStore.get(songId) ?: throw IOException("Песня не найдена")
        val tr = transcript(song, refresh)
        if (plain.isNullOrEmpty()) LineBuilder.fromTranscript(tr) else LineBuilder.align(plain, tr, song.durationMs)
    }
}
