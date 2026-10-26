package com.karaokemaker.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.karaokemaker.lyrics.LineBuilder
import com.karaokemaker.lyrics.Lrc
import com.karaokemaker.lyrics.LyricLine
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
            val (lines, source) = lyricsFromVocals(song, lrclib?.plain)
            SongStore.update(id) { it.copy(lyrics = lines, lyricsSource = source) }
        }
    }

    private suspend fun separate(id: String) {
        val token = Prefs.mvsepToken
        if (token.isBlank()) throw IOException("Укажите API-токен MVSEP в настройках, чтобы убрать основной вокал")
        var song = SongStore.get(id) ?: return
        var hash = song.mvsepHash
        if (hash == null) {
            progress(id, "Загружаю песню на MVSEP…")
            hash = Mvsep.createJob(token, SongStore.file(song, song.originalFile), Prefs.sepType, Prefs.addOpt1)
            song = SongStore.update(id) { it.copy(mvsepHash = hash) }!!
        }
        val started = System.currentTimeMillis()
        var job: Mvsep.Job
        while (true) {
            job = try {
                Mvsep.getJob(hash)
            } catch (e: IOException) {
                Mvsep.Job("retry", e.message.orEmpty(), emptyList())
            }
            when (job.status) {
                "done" -> break
                "failed", "error", "not_found" -> {
                    SongStore.update(id) { it.copy(mvsepHash = null) }
                    throw IOException("MVSEP не смог обработать песню: ${job.message.ifBlank { job.status }}")
                }
                "waiting" -> progress(id, "В очереди MVSEP… ${job.message}".trim())
                "processing" -> progress(id, "MVSEP отделяет основной вокал…")
                "distributing", "merging" -> progress(id, "MVSEP: почти готово…")
                else -> progress(id, "Ожидаю MVSEP… ${job.message}".trim())
            }
            if (System.currentTimeMillis() - started > 60 * 60_000L) {
                throw IOException("MVSEP слишком долго не отвечает. Попробуйте позже — нажмите «Повторить».")
            }
            delay(5_000)
        }
        if (job.files.isEmpty()) throw IOException("MVSEP вернул пустой результат")
        val names = mutableListOf<String>()
        job.files.forEachIndexed { i, f ->
            progress(id, "Скачиваю дорожки (${i + 1}/${job.files.size})…")
            val safe = "stem_${i}_" + f.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeLast(80)
            withContext(Dispatchers.IO) { Http.download(f.url, File(SongStore.dir(id), safe)) }
            names += safe
        }
        val (karaoke, vocals) = pickStems(names)
        SongStore.update(id) { it.copy(stems = names, karaokeFile = karaoke ?: names.first(), vocalsFile = vocals) }
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
        if (Prefs.whisperKey.isBlank()) {
            if (plain != null) {
                return Lrc.distribute(Lrc.plainLines(plain), song.durationMs) to
                    "LRCLIB без таймингов — поправьте время в редакторе (или добавьте ключ распознавания)"
            }
            return emptyList<LyricLine>() to "Текст не найден. Добавьте его в редакторе."
        }
        progress(song.id, "Распознаю текст по вокалу…")
        val tr = transcript(song)
        return if (plain != null) {
            LineBuilder.align(Lrc.plainLines(plain), tr, song.durationMs) to "LRCLIB, время выставлено по вокалу"
        } else {
            LineBuilder.fromTranscript(tr) to "Распознано нейросетью — проверьте текст в редакторе"
        }
    }

    /** Recognition result is cached in the song folder, so re-aligning edited text is free. */
    private fun transcript(song: Song, refresh: Boolean = false): Transcript {
        val cache = SongStore.file(song, "transcript.json")
        if (!refresh && cache.exists()) {
            runCatching { return Transcript.fromJson(JSONObject(cache.readText())) }
        }
        if (Prefs.whisperKey.isBlank()) throw IOException("Укажите ключ распознавания речи в настройках")
        val source = SongStore.file(song, song.vocalsFile ?: song.originalFile)
        val tr = Whisper.transcribe(source, Prefs.whisperUrl, Prefs.whisperKey, Prefs.whisperModel, Prefs.language)
        cache.writeText(tr.toJson().toString())
        return tr
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
