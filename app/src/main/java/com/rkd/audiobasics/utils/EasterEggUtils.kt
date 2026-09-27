package com.rkd.audiobasics.utils

import java.time.LocalDate

/**
 * Date-based easter eggs, checked against the device's local clock (minSdk 26 has native
 * java.time support, no desugaring needed for this). No anti-clock-tamper handling — these
 * are just for fun, not anything worth guarding against a changed system date.
 */
object EasterEggUtils {

    private const val ANNIVERSARY_MONTH = 2
    private const val ANNIVERSARY_DAY = 22

    private const val APRIL_FOOLS_MONTH = 4
    private const val APRIL_FOOLS_DAY = 1

    /** Feb 22 — the day Audiobasics v1.0 launched. */
    fun isAnniversaryDay(): Boolean {
        val today = LocalDate.now()
        return today.monthValue == ANNIVERSARY_MONTH && today.dayOfMonth == ANNIVERSARY_DAY
    }

    /** April 1st. */
    fun isAprilFoolsDay(): Boolean {
        val today = LocalDate.now()
        return today.monthValue == APRIL_FOOLS_MONTH && today.dayOfMonth == APRIL_FOOLS_DAY
    }
}
