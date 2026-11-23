package com.example.tai_chi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 * Simulates IMU boards whose clocks drift, sending a sample every 50 ms over a link with random
 * Bluetooth delays, and checks that ClockSync puts the samples back on the tablet's timeline.
 */
class ClockSyncTest {
    private val ms = 1_000_000L
    private val s = 1_000_000_000L
    private val minDelayNs = 3 * ms   // the fastest a packet ever gets through

    private data class Sample(val trueNs: Long, val estimateNs: Long)

    /**
     * One simulated board. [driftPpm] > 0 means its clock runs fast. [boardStartNs] is what its clock
     * reads when the tablet's reads [tabletStartNs]. Delays: 3-18 ms usually, sometimes up to 200 ms more.
     */
    private fun run(
        sync: ClockSync,
        driftPpm: Double,
        seconds: Int,
        boardStartNs: Double = 7.3 * s,
        tabletStartNs: Long = 1_000 * s,
        seed: Int = 1,
    ): List<Sample> {
        val rnd = Random(seed)
        val out = ArrayList<Sample>()
        for (i in 0 until seconds * 20) {
            val elapsed = i * 50 * ms
            val trueNs = tabletStartNs + elapsed
            val boardNs = boardStartNs + elapsed * (1 + driftPpm * 1e-6)
            val ticks = floor(boardNs * ClockSync.TICKS_PER_SECOND / s).toLong() and 0xFFFF_FFFFL
            var delay = minDelayNs + rnd.nextLong(15 * ms)
            if (rnd.nextInt(20) == 0) delay += rnd.nextLong(200 * ms)
            out += Sample(trueNs, sync.toTabletNs(ticks, trueNs + delay))
        }
        return out
    }

    /** Error of each estimate, ignoring the first few seconds while the estimate settles. */
    private fun errorsAfterSettling(samples: List<Sample>, settleSeconds: Int = 5) =
        samples.drop(settleSeconds * 20).map { it.estimateNs - it.trueNs }

    @Test
    fun estimateStaysWithin2msOfTrueTimeDespiteDrift() {
        // The XIAO's clock runs from a 32.768 kHz crystal (about ±20 ppm); ±100 ppm leaves a wide margin.
        // Limit: the 10 s window lags by drift x 10 s, so a clock 250 ppm off (no crystal) would be ~3 ms out.
        for (drift in listOf(-100.0, -40.0, 0.0, 40.0, 100.0)) {
            val errors = errorsAfterSettling(run(ClockSync(), drift, seconds = 600))
            // The estimate is late by the fastest possible delivery time (the same for both boards,
            // so it cancels when comparing them), give or take 2 ms.
            val worst = errors.maxOf { abs(it - minDelayNs) }
            assertTrue("drift $drift ppm: worst error ${worst / 1e6} ms", worst < 2 * ms)
        }
    }

    @Test
    fun leftAndRightLineUpWithin2msWhenDriftingInOppositeDirections() {
        val left = errorsAfterSettling(run(ClockSync(), +40.0, seconds = 600, boardStartNs = 3.0 * s, seed = 2))
        val right = errorsAfterSettling(run(ClockSync(), -40.0, seconds = 600, boardStartNs = 95.0 * s, seed = 3))
        // Without correction these two clocks would be 48 ms apart after 10 minutes, plus jitter.
        val worstSkew = left.zip(right).maxOf { (l, r) -> abs(l - r) }
        assertTrue("worst left/right skew ${worstSkew / 1e6} ms", worstSkew < 2 * ms)
    }

    @Test
    fun timeNeverRunsBackwards() {
        val estimates = run(ClockSync(), 40.0, seconds = 300).map { it.estimateNs }
        assertTrue(estimates.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun measuresDriftRate() {
        val sync = ClockSync()
        run(sync, 40.0, seconds = 30)
        assertNull("too early to report drift", sync.driftPpm)
        val sync2 = ClockSync()
        run(sync2, 40.0, seconds = 300)
        val ppm = sync2.driftPpm
        assertNotNull(ppm)
        assertEquals(40.0, ppm!!, 8.0)
    }

    @Test
    fun recoversWhenTheBoardRestarts() {
        val sync = ClockSync()
        run(sync, 40.0, seconds = 60, boardStartNs = 500.0 * s)
        // Board restarts: its clock begins again near zero while the tablet's keeps going.
        val after = run(sync, 40.0, seconds = 60, boardStartNs = 0.2 * s, tabletStartNs = 1_100 * s)
        val worst = errorsAfterSettling(after).maxOf { abs(it - minDelayNs) }
        assertTrue("worst error after restart ${worst / 1e6} ms", worst < 2 * ms)
    }

    @Test
    fun handlesTheTickCounterRollingOver() {
        // Start 20 s before the 32-bit tick counter wraps around.
        val nearWrap = (0x1_0000_0000L - 20 * ClockSync.TICKS_PER_SECOND).toDouble() / ClockSync.TICKS_PER_SECOND * s
        val worst = errorsAfterSettling(run(ClockSync(), 40.0, seconds = 60, boardStartNs = nearWrap))
            .maxOf { abs(it - minDelayNs) }
        assertTrue("worst error across the wrap ${worst / 1e6} ms", worst < 2 * ms)
    }
}
