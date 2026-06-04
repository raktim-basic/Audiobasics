package com.yt.lite

import android.app.Application
import com.yt.lite.api.cipher.CipherDeobfuscator
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class AudiobasicsApp : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (priority >= android.util.Log.WARN) {
                        android.util.Log.println(priority, tag ?: "Audiobasics", message)
                        t?.let { android.util.Log.e(tag ?: "Audiobasics", message, it) }
                    }
                }
            })
        }

        CipherDeobfuscator.initialize(this)
        Timber.d("AudiobasicsApp started")
    }
}
