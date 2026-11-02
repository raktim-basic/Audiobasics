package com.rkd.audiobasics.utils

import java.util.Calendar
import kotlin.random.Random

/**
 * The time of day, as the Home greeting and a few small touches see it. Uses the device's local
 * clock (no anti-clock-tamper handling, same as the other date-based easter eggs).
 *
 *   00:00 - 03:29  late night     ("Up late....", search says "go to sleep...")
 *   03:30 - 05:59  early morning  ("So early!!")
 *   06:00 - 11:59  morning
 *   12:00 - 17:29  afternoon
 *   17:30 - 23:59  evening
 */
object NightOwl {

    enum class Slot { LATE_NIGHT, EARLY_MORNING, MORNING, AFTERNOON, EVENING }

    fun slot(now: Calendar = Calendar.getInstance()): Slot {
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return when {
            minutes < 3 * 60 + 30 -> Slot.LATE_NIGHT
            minutes < 6 * 60 -> Slot.EARLY_MORNING
            minutes < 12 * 60 -> Slot.MORNING
            minutes < 17 * 60 + 30 -> Slot.AFTERNOON
            else -> Slot.EVENING
        }
    }

    /** True from midnight to 3:29 am. */
    fun isLateNight(): Boolean = slot() == Slot.LATE_NIGHT

    /**
     * The rare "There aren't any / Monsters nearby.." greeting in the late-night window. Rolled
     * once per app launch (1 in 10), never per redraw, so the greeting can't flicker between
     * two versions while the app is open.
     */
    val monstersNearby: Boolean by lazy { Random.nextInt(10) == 0 }
}
