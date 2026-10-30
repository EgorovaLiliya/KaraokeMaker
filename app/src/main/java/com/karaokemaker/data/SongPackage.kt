package com.karaokemaker.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A processed song packed into one zip ("Song.karaoke.zip"): song.json, the original, the backing track
 * and the cached recognition. It can be sent through any app — Bluetooth, Quick Share, messengers —
 * and opened on another phone without processing it again.
 */
object SongPackage {
    private const val MANIFEST = "song.json"
    private const val MAX_TOTAL_BYTES = 500L * 1024 * 1024

    fun shareIntent(context: Context, song: Song): Intent {
        val file = export(context, song)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Караоке: ${song.displayName}")
            putExtra(Intent.EXTRA_TEXT, "Караоке «${song.displayName}». Откройте файл в приложении KaraokeMaker.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Отправить песню")
    }

    private fun export(context: Context, song: Song): File {
        val dir = File(context.cacheDir, "share").apply {
            deleteRecursively()
            mkdirs()
        }
        val files = listOfNotNull(song.originalFile, song.karaokeFile, "transcript.json")
            .distinct()
            .map { SongStore.file(song, it) }
            .filter { it.exists() }
        val shared = song.copy(
            vocalsFile = null,
            stems = listOfNotNull(song.karaokeFile),
            mvsepHash = null,
            status = SongStatus.READY,
            progress = "",
            error = null,
        )
        val safeName = song.displayName.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80).ifBlank { "song" }
        val out = File(dir, "$safeName.karaoke.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            // Audio is already compressed; storing it as-is keeps export fast.
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(shared.toJson().toString().toByteArray())
            zip.closeEntry()
            files.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    /** True if the file is a KaraokeMaker package rather than plain audio. */
    fun isPackage(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                var checked = 0
                while (entry != null && checked < 10) {
                    if (entry.name == MANIFEST) return@runCatching true
                    entry = zip.nextEntry
                    checked++
                }
                false
            }
        } ?: false
    }.getOrDefault(false)

    /** Unpacks a received song into the library under a new id, so it never overwrites an existing one. */
    fun import(context: Context, uri: Uri): Song {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val dir = SongStore.dir(id).apply { mkdirs() }
        try {
            var manifest: JSONObject? = null
            var total = 0L
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    // Only plain file names: nothing from the archive may land outside the song folder.
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    if (name.isBlank() || name.startsWith(".")) continue
                    if (name == MANIFEST) {
                        manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        continue
                    }
                    File(dir, name).outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_TOTAL_BYTES) throw IOException("Файл слишком большой")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            val m = manifest ?: throw IOException("Это не песня KaraokeMaker")
            val received = Song.fromJson(m)
            if (!File(dir, received.originalFile).exists()) throw IOException("В файле нет аудио")
            val song = received.copy(
                id = id,
                karaokeFile = received.karaokeFile?.takeIf { File(dir, it).exists() },
                vocalsFile = null,
                stems = listOfNotNull(received.karaokeFile).filter { File(dir, it).exists() },
                mvsepHash = null,
                status = SongStatus.READY,
                progress = "",
                error = null,
                createdAt = System.currentTimeMillis(),
            )
            SongStore.save(song)
            return song
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }
}
