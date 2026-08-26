package com.rkd.audiobasics

import android.app.Application
import android.util.Log
import com.rkd.audiobasics.api.cipher.CipherDeobfuscator
import com.rkd.audiobasics.ui.DebugLogCollector
import com.rkd.audiobasics.utils.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class AudiobasicsApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // First thing, so it's in place before anything else has a chance to crash.
        CrashReporter.install(this)

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
