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

        if (BuildConfig.DEBUG) {
            Timber.plant(object : Timber.DebugTree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    super.log(priority, tag, message, t)  // still goes to logcat
                    DebugLogCollector.add(priority, tag, message + (t?.let { " | ${it.message}" } ?: ""))
                }
            })
        } else {
            Timber.plant(object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (priority >= Log.WARN) {
                        Log.println(priority, tag ?: "Audiobasics", message)
                    }
                }
            })
        }

        CipherDeobfuscator.initialize(this)
        Timber.d("AudiobasicsApp started")
    }
}
