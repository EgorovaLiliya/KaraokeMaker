package com.karaokemaker.net

import com.karaokemaker.lyrics.LineBuilder
import com.karaokemaker.lyrics.TimedText
import com.karaokemaker.lyrics.Transcript
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Speech recognition over any OpenAI-compatible /audio/transcriptions endpoint (Groq, OpenAI…). */
object Whisper {
    fun transcribe(audio: File, baseUrl: String, apiKey: String, model: String, language: String): Transcript {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("temperature", "0")
            .apply { if (language.isNotBlank()) addFormDataPart("language", language) }
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/mpeg".toMediaType()))
            .build()
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                if (resp.code == 403) {
                    throw IOException("сервис распознавания недоступен из вашей страны (HTTP 403). Включите VPN или выберите в настройках распознавание через MVSEP")
                }
                throw IOException("Распознавание: HTTP ${resp.code} ${msg ?: text.take(200)}")
            }
            return parse(JSONObject(text))
        }
    }

    private fun ms(v: Double) = (v * 1000).toLong()

    private fun parse(o: JSONObject): Transcript {
        val segments = mutableListOf<TimedText>()
        val banned = mutableListOf<LongRange>()
        o.optJSONArray("segments")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.getJSONObject(i)
                val t = TimedText(s.optString("text").trim(), ms(s.optDouble("start")), ms(s.optDouble("end")))
                val noSpeech = s.optDouble("no_speech_prob", 0.0)
                if (LineBuilder.isHallucination(t.text) || noSpeech > 0.8) banned += t.startMs..t.endMs
                else segments += t
            }
        }
        val words = mutableListOf<TimedText>()
        (o.optJSONArray("words") ?: JSONArray()).let { a ->
            for (i in 0 until a.length()) {
                val w = a.getJSONObject(i)
                val t = TimedText(w.optString("word").trim(), ms(w.optDouble("start")), ms(w.optDouble("end")))
                if (t.text.isNotEmpty() && banned.none { t.startMs in it }) words += t
            }
        }
        return Transcript(words, segments)
    }
}
