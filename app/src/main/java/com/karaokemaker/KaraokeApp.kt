package com.karaokemaker

import android.app.Application
import com.karaokemaker.data.Prefs
import com.karaokemaker.data.SongStore

class KaraokeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        SongStore.init(this)
    }
}
