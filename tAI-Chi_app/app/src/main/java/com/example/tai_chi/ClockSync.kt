package com.example.tai_chi

/**
 * Converts one IMU board's clock into tablet time (SystemClock.elapsedRealtimeNanos), so readings
 * from both boards and the camera can be put on the same timeline.
 *
 * Nothing about the board's clock is hardcoded. Each packet carries the board's time when the sample
 * was taken; the tablet notes when the packet arrived. The difference is
 *
 *     arrival - boardTime = offset (how far apart the clocks are) + delay (Bluetooth, always >= 0)
 *
 * The smallest difference over the last [windowNs] came from the packet that was delayed least, so
 * it is the best measurement of the offset. Because the window slides, the offset is re-measured
 * continuously, which keeps up with the board's clock drifting (about 0.4 ms per 10 s at 40 ppm).
 *
 * Plain Kotlin with no Android calls, so it is covered by JVM unit tests (ClockSyncTest).
 * Thread-safe: Bluetooth callbacks and the UI may call it from different threads.
 */
class ClockSync(
    private val windowNs: Long = 10_000_000_000L,       // how far back to look for the fastest packet
    private val driftAfterNs: Long = 60_000_000_000L,   // wait this long before reporting drift
) {
    private val recent = ArrayDeque<Pair<Long, Long>>()  // (arrival time, arrival - board time)
    private var lastTicks = -1L
    private var wraps = 0L
    private var lastOutNs = Long.MIN_VALUE
    private var startArrivalNs = 0L
    private var reference: Pair<Long, Long>? = null       // (arrival, offset) once settled, for drift

    /** Current estimate of tablet time minus board time, in nanoseconds. */
    @get:Synchronized var offsetNs = 0L
        private set

    /** How late the most recent packet arrived compared with the fastest one in the window. */
    @get:Synchronized var lastDelayNs = 0L
        private set

    /** True once there are a few seconds of packets, so the offset estimate is reliable. */
    @get:Synchronized var settled = false
        private set

    /**
     * How fast the board's clock is drifting from the tablet's, in parts per million (positive = the
     * board runs fast), measured from how the offset changes over time. Only for display: the offset
     * already follows the drift. Null until there is at least [driftAfterNs] of data.
     */
    @get:Synchronized var driftPpm: Double? = null
        private set

    /** Starts over. Call when the board connects or reconnects: it may have restarted. */
    @Synchronized
    fun reset() {
        recent.clear()
        lastTicks = -1L
        wraps = 0L
        settled = false
        reference = null
        driftPpm = null
        // lastOutNs is kept so converted times stay in order across reconnects.
    }

    /**
     * Converts a sample's board time to tablet time.
     * @param ticks the board's uint32 tick counter when the sample was taken ([TICKS_PER_SECOND] per second)
     * @param arrivalNs SystemClock.elapsedRealtimeNanos() when the packet arrived
     */
    @Synchronized
    fun toTabletNs(ticks: Long, arrivalNs: Long): Long {
        if (lastTicks >= 0 && ticks < lastTicks) {
            if (lastTicks - ticks > HALF_RANGE) wraps++   // the 32-bit counter rolled over (every 48 days)
            else reset()                                  // went backwards: the board restarted
        }
        if (lastTicks < 0) startArrivalNs = arrivalNs
        lastTicks = ticks

        val boardNs = ticksToNs((wraps shl 32) + ticks)
        val difference = arrivalNs - boardNs
        recent.addLast(arrivalNs to difference)
        while (arrivalNs - recent.first().first > windowNs) recent.removeFirst()
        offsetNs = recent.minOf { it.second }
        lastDelayNs = difference - offsetNs
        settled = arrivalNs - startArrivalNs >= SETTLE_NS

        if (settled) {
            val ref = reference ?: (arrivalNs to offsetNs).also { reference = it }
            val elapsed = arrivalNs - ref.first
            // A board clock that runs fast pulls ahead, so the offset shrinks: report that as positive.
            if (elapsed >= driftAfterNs) driftPpm = -(offsetNs - ref.second).toDouble() / elapsed * 1e6
        }

        // Never let time run backwards: when a faster packet lowers the offset, hold instead.
        val t = boardNs + offsetNs
        val out = if (lastOutNs != Long.MIN_VALUE && t <= lastOutNs) lastOutNs + 1 else t
        lastOutNs = out
        return out
    }

    companion object {
        /** Board clock rate: FreeRTOS ticks on the XIAO nRF52840, driven by its 32.768 kHz crystal. */
        const val TICKS_PER_SECOND = 1024L
        private const val HALF_RANGE = 1L shl 31
        private const val SETTLE_NS = 3_000_000_000L

        // Split so large tick counts cannot overflow a Long.
        fun ticksToNs(ticks: Long): Long =
            ticks / TICKS_PER_SECOND * 1_000_000_000L + ticks % TICKS_PER_SECOND * 1_000_000_000L / TICKS_PER_SECOND
    }
}
