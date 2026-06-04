package com.yt.lite

import android.app.Application
import android.util.Log
import com.yt.lite.api.cipher.CipherDeobfuscator
import com.yt.lite.ui.DebugLogCollector
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class AudiobasicsApp : Application() {
    override fun onCreate() {
        super.onCreate()

        Timber.plant(object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                super.log(priority, tag, message, t)
                DebugLogCollector.add(
                    priority,
                    tag,
                    message + (t?.let { " | ${it.message}" } ?: "")
                )
            }
        })

        CipherDeobfuscator.initialize(this)
        Timber.d("AudiobasicsApp started")
    }
}
