package com.example.poseengine

/*
 * Port of the matching logic in coach.py: step through the reference keyframes one by one; a pose counts
 * once the live joint angles stay within the threshold for [holdMs]. Pure Kotlin (JVM-testable).
 */
internal enum class CoachStatus { NO_PERSON, TOO_FEW_JOINTS, ADJUST, HOLDING, COMPLETE }

internal data class CoachState(
    val index: Int,                 // 0-based target pose (== total when finished)
    val total: Int,
    val status: CoachStatus,
    val meanError: Double,          // NaN when not computed
    val cues: List<String>,         // up to 2 corrections, worst joint first
    val holdFraction: Float,        // 0..1 while HOLDING
    val flashing: Boolean,          // "Pose matched!" banner (1 s after advancing)
)

/** diff = live - reference. A bigger elbow/knee/hip angle means straighter, so too big -> bend. */
internal fun cueFor(jointName: String, diff: Double): String {
    val side = if (jointName.startsWith("L_")) "left" else "right"
    val part = jointName.substring(2)
    return if (part == "shoulder") {
        "${if (diff > 0) "lower" else "raise"} your $side arm"
    } else {
        "${if (diff > 0) "bend" else "straighten"} your $side $part"
    }
}

internal class TaiChiCoach(
    private val targets: List<DoubleArray>,
    val threshold: Double = 20.0,       // max mean joint-angle error (deg), as coach.py --threshold
    private val holdMs: Long = 600,     // as coach.py --hold 0.6
    private val flashMs: Long = 1000,
) {
    private var idx = 0
    private var holdStart = -1L
    private var flashUntil = Long.MIN_VALUE

    val total: Int get() = targets.size
    @get:Synchronized val index: Int get() = idx

    @Synchronized fun restart() { idx = 0; holdStart = -1 }
    @Synchronized fun next() { idx = minOf(idx + 1, targets.size); holdStart = -1 }
    @Synchronized fun previous() { idx = maxOf(idx - 1, 0); holdStart = -1 }

    /** [angles] = live joint angles (NaN where unseen), or null when no person was detected. */
    @Synchronized
    fun update(angles: DoubleArray?, nowMs: Long): CoachState {
        val flashing = nowMs < flashUntil
        fun state(status: CoachStatus, err: Double = Double.NaN, cues: List<String> = emptyList(), hold: Float = 0f) =
            CoachState(idx, targets.size, status, err, cues, hold, flashing)

        if (idx >= targets.size) { holdStart = -1; return state(CoachStatus.COMPLETE) }
        if (angles == null) { holdStart = -1; return state(CoachStatus.NO_PERSON) }

        val target = targets[idx]
        val err = DoubleArray(target.size) { Math.abs(angles[it] - target[it]) }   // NaN if either side is NaN
        val valid = BooleanArray(err.size) { !err[it].isNaN() }
        val nValid = valid.count { it }
        if (nValid < 4) { holdStart = -1; return state(CoachStatus.TOO_FEW_JOINTS) }

        var sum = 0.0; var max = 0.0
        for (i in err.indices) if (valid[i]) { sum += err[i]; if (err[i] > max) max = err[i] }
        val mean = sum / nValid
        val matched = mean < threshold && max < 2 * threshold

        if (matched) {
            if (holdStart < 0) holdStart = nowMs
            val held = nowMs - holdStart
            if (held >= holdMs) {
                idx += 1
                holdStart = -1
                flashUntil = nowMs + flashMs
                return CoachState(idx, targets.size, if (idx >= targets.size) CoachStatus.COMPLETE else CoachStatus.ADJUST,
                    mean, emptyList(), 0f, true)
            }
            return state(CoachStatus.HOLDING, mean, hold = (held.toFloat() / holdMs).coerceIn(0f, 1f))
        }

        holdStart = -1
        val worst = err.indices.sortedByDescending { if (valid[it]) err[it] else -1.0 }.take(2)
        val cues = worst.filter { valid[it] && err[it] > threshold * 0.75 }
            .map { cueFor(JOINT_NAMES[it], angles[it] - target[it]) }
        return state(CoachStatus.ADJUST, mean, cues)
    }
}
