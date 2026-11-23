package com.example.tai_chi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryLevelTest {
    @Test
    fun fullAndEmpty() {
        assertEquals(100, BatteryLevel.percent(4200))
        assertEquals(100, BatteryLevel.percent(4350))   // above full, e.g. just off the charger
        assertEquals(0, BatteryLevel.percent(3270))
        assertEquals(0, BatteryLevel.percent(2500))     // no battery or a deeply discharged one
    }

    @Test
    fun pointsOnTheCurve() {
        assertEquals(50, BatteryLevel.percent(3840))
        assertEquals(20, BatteryLevel.percent(3730))
        assertEquals(80, BatteryLevel.percent(4020))
    }

    @Test
    fun interpolatesBetweenPoints() {
        assertEquals(78, BatteryLevel.percent(4004))   // 60% of the way from 3980 mV (75%) to 4020 mV (80%)
    }

    @Test
    fun neverIncreasesAsVoltageFalls() {
        var previous = 101
        for (mv in 4300 downTo 3000 step 5) {
            val p = BatteryLevel.percent(mv)
            assertTrue("$mv mV -> $p% after $previous%", p <= previous)
            previous = p
        }
    }
}
