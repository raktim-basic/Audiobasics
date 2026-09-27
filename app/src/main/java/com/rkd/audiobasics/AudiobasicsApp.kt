package com.rkd.audiobasics

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.Log
import com.rkd.audiobasics.api.cipher.CipherDeobfuscator
import com.rkd.audiobasics.ui.DebugLogCollector
import com.rkd.audiobasics.utils.EasterEggUtils
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
        applyAnniversaryIcon()
        Timber.d("AudiobasicsApp started")
    }

    // Feb 22 anniversary icon easter egg: exactly one of the two activity-aliases in the
    // manifest is enabled at a time, cheap to re-check on every process start (the PackageManager
    // calls are no-ops if the state already matches). Some launchers pick up the swapped icon
    // instantly; others only re-index it the next time they refresh (reopening the app, or a
    // home-screen/launcher restart).
    private fun applyAnniversaryIcon() {
        val defaultAlias = ComponentName(this, "com.rkd.audiobasics.MainActivityDefault")
        val anniversaryAlias = ComponentName(this, "com.rkd.audiobasics.MainActivityAnniversary")
        val isAnniversary = EasterEggUtils.isAnniversaryDay()

        packageManager.setComponentEnabledSetting(
            defaultAlias,
            if (isAnniversary) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        packageManager.setComponentEnabledSetting(
            anniversaryAlias,
            if (isAnniversary) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}

