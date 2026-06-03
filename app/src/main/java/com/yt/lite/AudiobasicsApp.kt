package com.yt.lite

import android.app.Application
import com.yt.lite.api.cipher.CipherDeobfuscator
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AudiobasicsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CipherDeobfuscator.initialize(this)
    }
}
