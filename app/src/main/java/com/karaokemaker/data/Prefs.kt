package com.karaokemaker.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val DEFAULT_SEP_TYPE = 49
    const val DEFAULT_SEP_NAME = "MelBand Karaoke (lead/back vocals)"
    const val DEFAULT_WHISPER_URL = "https://api.groq.com/openai/v1"
    const val DEFAULT_WHISPER_MODEL = "whisper-large-v3"
    const val DEFAULT_MVSEP_WHISPER = 32
    const val RECOGNISER_MVSEP = "mvsep"
    const val RECOGNISER_OPENAI = "openai"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private fun str(key: String, def: String = "") = sp.getString(key, def) ?: def
    private fun put(key: String, value: String) = sp.edit().putString(key, value.trim()).apply()

    var mvsepToken: String
        get() = str("mvsep_token")
        set(v) = put("mvsep_token", v)

    var sepType: Int
        get() = sp.getInt("sep_type", DEFAULT_SEP_TYPE)
        set(v) = sp.edit().putInt("sep_type", v).apply()

    var sepName: String
        get() = str("sep_name", DEFAULT_SEP_NAME)
        set(v) = put("sep_name", v)

    var addOpt1: String
        get() = str("add_opt1")
        set(v) = put("add_opt1", v)

    var whisperUrl: String
        get() = str("whisper_url", DEFAULT_WHISPER_URL)
        set(v) = put("whisper_url", v)

    var whisperKey: String
        get() = str("whisper_key")
        set(v) = put("whisper_key", v)

    var whisperModel: String
        get() = str("whisper_model", DEFAULT_WHISPER_MODEL)
        set(v) = put("whisper_model", v)

    /** Where lyrics are recognised: MVSEP Whisper (works from Russia) or an OpenAI-compatible API. */
    var recogniser: String
        get() = str("recogniser", RECOGNISER_MVSEP)
        set(v) = put("recogniser", v)

    var mvsepWhisperId: Int
        get() = sp.getInt("mvsep_whisper_id", 0)
        set(v) = sp.edit().putInt("mvsep_whisper_id", v).apply()

    var mvsepWhisperOpt: String
        get() = str("mvsep_whisper_opt")
        set(v) = put("mvsep_whisper_opt", v)

    /** "" = auto, otherwise ISO code such as "ru" or "en". */
    var language: String
        get() = str("language")
        set(v) = put("language", v)

    /** How many seconds before a tapped line playback starts. */
    var prerollSec: Int
        get() = sp.getInt("preroll", 4)
        set(v) = sp.edit().putInt("preroll", v).apply()

    var lastTvAddress: String
        get() = str("last_tv")
        set(v) = put("last_tv", v)
}
