package com.karaokemaker.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "KaraokeMaker/0.1 (https://github.com/EgorovaLiliya/KaraokeMaker)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    fun download(url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".part")
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Не удалось скачать файл: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Пустой ответ сервера")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить ${target.name}")
    }
}
