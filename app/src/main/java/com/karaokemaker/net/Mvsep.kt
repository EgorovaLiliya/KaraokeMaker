package com.karaokemaker.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException

/** Client for the MVSEP separation API (https://mvsep.com/full_api). */
object Mvsep {
    private const val BASE = "https://mvsep.com/api"

    data class Option(val key: String, val label: String)
    data class Field(val name: String, val label: String, val options: List<Option>)
    data class Algorithm(val id: Int, val name: String, val fields: List<Field>)
    data class StemFile(val name: String, val url: String)
    /** [raw] is the whole status response: some algorithms (Whisper) return their result outside `files`. */
    data class Job(val status: String, val message: String, val files: List<StemFile>, val raw: JSONObject? = null)

    /** [options] are the algorithm's extra fields, e.g. add_opt1 → value. */
    fun createJob(token: String, audio: File, sepType: Int, options: Map<String, String>): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_token", token)
            .addFormDataPart("sep_type", sepType.toString())
            .addFormDataPart("output_format", "0") // mp3 320 kbps
            .addFormDataPart("is_demo", "0")
            .apply { options.filterValues { it.isNotBlank() }.forEach { (k, v) -> addFormDataPart(k, v) } }
            .addFormDataPart("audiofile", audio.name, audio.asRequestBody("audio/mpeg".toMediaType()))
            .build()
        val req = Request.Builder().url("$BASE/separation/create").post(body).build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = parse(text) as? JSONObject
                ?: throw IOException("MVSEP: HTTP ${resp.code} ${text.take(200)}")
            if (!json.optBoolean("success")) throw IOException("MVSEP: " + errorText(json, resp.code))
            return json.optJSONObject("data")?.optString("hash")?.ifEmpty { null }
                ?: throw IOException("MVSEP не вернул номер задачи")
        }
    }

    fun getJob(hash: String): Job {
        val req = Request.Builder().url("$BASE/separation/get?hash=$hash").build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = parse(text) as? JSONObject
                ?: throw IOException("MVSEP: HTTP ${resp.code} ${text.take(200)}")
            val data = json.optJSONObject("data")
            val files = mutableListOf<StemFile>()
            when (val f = data?.opt("files")) {
                is JSONArray -> for (i in 0 until f.length()) f.optJSONObject(i)?.let { stem(it)?.let(files::add) }
                is JSONObject -> f.keys().forEach { k -> f.optJSONObject(k)?.let { stem(it)?.let(files::add) } }
            }
            val message = data?.optString("message").orEmpty().ifEmpty { json.optString("message") }
            return Job(json.optString("status").lowercase(), message, files, json)
        }
    }

    fun algorithms(): List<Algorithm> {
        val req = Request.Builder().url("$BASE/app/algorithms").build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("MVSEP: HTTP ${resp.code}")
            val root = parse(text)
            val items = mutableListOf<JSONObject>()
            fun collect(v: Any?) {
                when (v) {
                    is JSONArray -> for (i in 0 until v.length()) v.optJSONObject(i)?.let(items::add)
                    is JSONObject -> if (v.has("render_id")) items += v else {
                        if (v.has("data")) collect(v.opt("data")) else v.keys().forEach { collect(v.opt(it)) }
                    }
                }
            }
            collect(root)
            return items.mapNotNull { o ->
                val id = o.optInt("render_id", -1)
                if (id < 0) return@mapNotNull null
                val fields = mutableListOf<Field>()
                val fa = o.optJSONArray("algorithm_fields")
                if (fa != null) for (i in 0 until fa.length()) {
                    val fo = fa.optJSONObject(i) ?: continue
                    val opts = when (val raw = fo.opt("options")) {
                        is String -> parse(raw)
                        else -> raw
                    }
                    val options = mutableListOf<Option>()
                    when (opts) {
                        is JSONObject -> opts.keys().forEach { k -> options += Option(k, opts.optString(k)) }
                        is JSONArray -> for (k in 0 until opts.length()) options += Option(k.toString(), opts.optString(k))
                    }
                    fields += Field(fo.optString("name"), fo.optString("text").ifEmpty { fo.optString("name") }, options)
                }
                Algorithm(id, o.optString("name"), fields)
            }
        }
    }

    private fun stem(o: JSONObject): StemFile? {
        val url = o.optString("url").ifEmpty { o.optString("link") }
        if (url.isEmpty()) return null
        val name = o.optString("download").ifEmpty { url.substringAfterLast('/').substringBefore('?') }
        return StemFile(name, url)
    }

    private fun errorText(json: JSONObject, code: Int): String {
        val data = json.opt("data")
        val parts = mutableListOf<String>()
        fun add(v: Any?) {
            when (v) {
                is String -> if (v.isNotBlank()) parts += v
                is JSONArray -> for (i in 0 until v.length()) add(v.opt(i))
                is JSONObject -> v.keys().forEach { add(v.opt(it)) }
            }
        }
        if (data is JSONObject) add(data.opt("message")) else add(data)
        add(json.opt("errors"))
        add(json.opt("message"))
        return parts.joinToString("; ").ifEmpty { "ошибка HTTP $code" }
    }

    private fun parse(text: String): Any? = runCatching { JSONTokener(text).nextValue() }.getOrNull()
}
