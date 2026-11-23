package com.example.tai_chi

/**
 * Battery state of one band, as reported in its IMU packets (see XIAOinterfaceAppTablet.ino).
 * [millivolts] is the LiPo cell voltage; [charging] is true while it charges over USB-C.
 */
data class Battery(val millivolts: Int, val charging: Boolean) {
    val percent: Int get() = BatteryLevel.percent(millivolts)
    val volts: Double get() = millivolts / 1000.0
}

/**
 * Converts a single-cell LiPo voltage to an approximate charge percentage.
 *
 * A LiPo's voltage does not fall evenly as it discharges: it drops quickly from 4.2 V, stays around
 * 3.7-3.9 V for most of the charge, then falls off sharply below about 3.6 V. So the percentage comes
 * from a typical discharge curve rather than a straight line. Expect about ±10%: the exact curve depends
 * on the cell, its age, temperature, and the load at that moment.
 *
 * Plain Kotlin, covered by BatteryLevelTest.
 */
object BatteryLevel {
    // (millivolts, percent), highest first. Typical resting curve of a 3.7 V LiPo cell.
    private val CURVE = listOf(
        4200 to 100, 4150 to 95, 4110 to 90, 4080 to 85, 4020 to 80, 3980 to 75,
        3950 to 70, 3910 to 65, 3870 to 60, 3850 to 55, 3840 to 50, 3820 to 45,
        3800 to 40, 3790 to 35, 3770 to 30, 3750 to 25, 3730 to 20, 3710 to 15,
        3690 to 10, 3610 to 5, 3270 to 0,
    )

    /** Below this the band should be charged soon. */
    const val LOW_PERCENT = 15

    fun percent(millivolts: Int): Int {
        if (millivolts >= CURVE.first().first) return 100
        if (millivolts <= CURVE.last().first) return 0
        for (i in 0 until CURVE.size - 1) {
            val (hiMv, hiPct) = CURVE[i]
            val (loMv, loPct) = CURVE[i + 1]
            if (millivolts >= loMv) {
                val fraction = (millivolts - loMv).toDouble() / (hiMv - loMv)
                return Math.round(loPct + fraction * (hiPct - loPct)).toInt()
            }
        }
        return 0
    }
}
