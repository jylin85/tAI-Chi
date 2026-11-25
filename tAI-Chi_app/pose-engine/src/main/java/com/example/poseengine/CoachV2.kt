package com.example.poseengine

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Port of coach v2 (matching.py, joints.problem_key, and the main loop of coach_v2.py).
 * Pure Kotlin (no Android types) so it can be unit-tested on the JVM.
 *
 * Each keyframe has its own target angle, tolerance and weight per joint:
 *   weight 1   -> critical joint, must be within its tolerance
 *   weight 0.5 -> secondary joint, gets `lenient` (1.5x) the tolerance
 *   weight 0   -> ignored
 * The score is the WORST joint's points (100 at the target, 70 at the edge of the allowed range, 0 at 3x),
 * so score >= 70 exactly when the pose matches.
 */

/** One keyframe of reference_v2/reference.json (made by build_reference.py). Arrays are in [JOINT_NAMES] order. */
internal class V2Keyframe(
    val id: String,
    val movementNo: Int,
    val movementName: String,
    val subName: String,
    val holdS: Double,
    val instruction: String,
    val keyPoints: String,
    val mistakes: String,
    val target: DoubleArray,
    val tol: DoubleArray,
    val weight: DoubleArray,
    /** Expert-written cues keyed by (joint name, problem key), e.g. ("L_knee", "too_straight"); "any" matches every problem. */
    val cues: Map<Pair<String, String>, String> = emptyMap(),
    val thumbnail: String? = null,
    val thumbnailFlip: Boolean = false,
) {
    val poseName: String get() = "$movementName $subName".trim()
}

internal class PoseScore(
    val z: DoubleArray,          // |live - target| / tolerance, NaN where not judged or not seen
    val bad: List<Int>,          // seen joints outside their allowed range, ascending index
    val unseen: List<Int>,       // critical joints that are not visible
    val score: Double,           // 0..100
    val matched: Boolean,
)

/** Problem key used by the expert cue table. [diff] = live angle - target angle. */
internal fun problemKey(joint: String, diff: Double): String =
    if (joint.endsWith("shoulder")) (if (diff > 0) "arm_too_high" else "arm_too_low")
    else (if (diff > 0) "too_straight" else "too_bent")

/** "left knee", "right shoulder" ... */
internal fun jointLabel(joint: String): String =
    (if (joint.startsWith("L_")) "left " else "right ") + joint.substring(2)

/** Fallback correction when the expert wrote none for this joint. [diff] = live - target. */
internal fun autoCue(joint: String, diff: Double): String {
    val side = if (joint.startsWith("L_")) "left" else "right"
    val part = joint.substring(2)
    val off = abs(diff).roundToInt()
    return if (part == "shoulder") "${if (diff > 0) "Lower" else "Raise"} your $side arm ($off° off)"
    else "${if (diff > 0) "Bend" else "Straighten"} your $side $part more ($off° off)"
}

internal fun scorePose(live: DoubleArray, kf: V2Keyframe, lenient: Double = 1.5, tolScale: Double = 1.0): PoseScore {
    val n = kf.target.size
    val z = DoubleArray(n) { Double.NaN }
    val bad = ArrayList<Int>()
    val unseen = ArrayList<Int>()
    var judged = 0
    var seen = 0
    var minPts = Double.POSITIVE_INFINITY
    for (j in 0 until n) {
        val w = kf.weight[j]
        val tgt = kf.target[j]
        val tol = kf.tol[j] * tolScale
        if (w <= 0 || tgt.isNaN() || tol.isNaN()) continue
        judged++
        if (live[j].isNaN()) {
            if (w >= 1) unseen += j
            continue
        }
        seen++
        z[j] = abs(live[j] - tgt) / tol
        val limit = if (w >= 1) 1.0 else lenient
        if (z[j] > limit) bad += j
        val ze = z[j] / limit
        val pts = if (ze <= 1) 100 - 30 * ze else (70 * (3 - ze) / 2).coerceIn(0.0, 70.0)
        if (pts < minPts) minPts = pts
    }
    val score = if (seen > 0) minPts else 0.0
    val need = minOf(3, judged)
    val matched = need > 0 && seen >= need && bad.isEmpty() && unseen.isEmpty()
    return PoseScore(z, bad, unseen, score, matched)
}

/** A correction to show, and whether the expert wrote it (true) or it was generated (false). */
internal data class Cue(val text: String, val expert: Boolean)

/** For each out-of-range joint, critical and worst first: expert cue for (joint, problem) -> (joint, "any") -> auto cue. */
internal fun selectCues(kf: V2Keyframe, live: DoubleArray, res: PoseScore, maxCues: Int = 2): List<Cue> =
    res.bad.sortedWith(compareBy<Int>({ -kf.weight[it] }, { -res.z[it] }))
        .take(maxCues)
        .map { j ->
            val name = JOINT_NAMES[j]
            val diff = live[j] - kf.target[j]
            val msg = kf.cues[name to problemKey(name, diff)] ?: kf.cues[name to "any"]
            if (msg != null) Cue(msg, true) else Cue(autoCue(name, diff), false)
        }

internal enum class V2Status { NO_PERSON, CANT_SEE, ADJUST, HOLDING, COMPLETE }

internal data class V2State(
    val index: Int,                  // 0-based current keyframe (== total when finished)
    val total: Int,
    val status: V2Status,
    val score: Double,               // NaN when not computed
    val cues: List<Cue>,
    val unseen: List<String>,        // labels of critical joints that cannot be seen (max 2)
    val holdFraction: Float,         // 0..1 while HOLDING
    val banner: String?,             // "Matched! Next: ..." / "Sequence complete - well done!"
    val averageMatched: Double,      // average best score over matched poses (for the COMPLETE screen)
)

/** Mirrors the main loop of coach_v2.py: per-keyframe hold time, skip/previous/restart, best score per pose. */
internal class TaiChiCoachV2(
    private val keyframes: List<V2Keyframe>,
    private val tolScale: Double = 1.0,      // coach_v2.py --tol-scale (>1 more forgiving)
    private val holdScale: Double = 1.0,     // coach_v2.py --hold-scale
    private val matchBannerMs: Long = 3000,
    private val completeBannerMs: Long = 4000,
) {
    enum class PoseResult { NOT_REACHED, MATCHED, SKIPPED }

    private var idx = 0
    private var holdStart = -1L
    private var banner: String? = null
    private var bannerUntil = Long.MIN_VALUE
    private val best = DoubleArray(keyframes.size)
    private val results = Array(keyframes.size) { PoseResult.NOT_REACHED }

    val total: Int get() = keyframes.size
    val movementCount: Int get() = keyframes.map { it.movementNo }.distinct().size
    @get:Synchronized val index: Int get() = idx

    /** Keyframe to show: the current one, or the last one after the routine is complete. */
    fun keyframe(i: Int = idx): V2Keyframe = keyframes[i.coerceIn(0, keyframes.size - 1)]

    @Synchronized fun bestScore(i: Int): Double = best[i]
    @Synchronized fun result(i: Int): PoseResult = results[i]

    @Synchronized fun restart() {
        idx = 0; holdStart = -1; banner = null
        best.fill(0.0); results.fill(PoseResult.NOT_REACHED)
    }

    @Synchronized fun next() { if (idx < keyframes.size) advance(idx + 1, PoseResult.SKIPPED, Long.MIN_VALUE) }
    @Synchronized fun previous() { if (idx > 0) { idx -= 1; holdStart = -1 } }

    private fun advance(to: Int, how: PoseResult, nowMs: Long) {
        results[idx] = how
        idx = to
        holdStart = -1
        if (nowMs == Long.MIN_VALUE) return       // manual skip (Skip button): no banner
        if (how == PoseResult.MATCHED && to < keyframes.size) {
            banner = "Matched!  Next: ${keyframes[to].poseName}".trim()
            bannerUntil = nowMs + matchBannerMs
        } else if (to >= keyframes.size) {
            banner = "Sequence complete - well done!"
            bannerUntil = nowMs + completeBannerMs
        }
    }

    private fun averageMatched(): Double {
        val matched = best.indices.filter { results[it] == PoseResult.MATCHED }
        return if (matched.isEmpty()) 0.0 else matched.sumOf { best[it] } / matched.size
    }

    /** [live] = joint angles in [JOINT_NAMES] order (NaN where unseen), or null when no person was detected. */
    @Synchronized
    fun update(live: DoubleArray?, nowMs: Long): V2State {
        fun state(status: V2Status, score: Double = Double.NaN, cues: List<Cue> = emptyList(),
                  unseen: List<String> = emptyList(), hold: Float = 0f) =
            V2State(idx, keyframes.size, status, score, cues, unseen, hold,
                if (nowMs < bannerUntil) banner else null, averageMatched())

        if (idx >= keyframes.size) return state(V2Status.COMPLETE)
        if (live == null) { holdStart = -1; return state(V2Status.NO_PERSON) }

        val kf = keyframes[idx]
        val res = scorePose(live, kf, tolScale = tolScale)
        if (res.score > best[idx]) best[idx] = res.score

        if (res.unseen.isNotEmpty()) {
            holdStart = -1
            return state(V2Status.CANT_SEE, res.score, unseen = res.unseen.take(2).map { jointLabel(JOINT_NAMES[it]) })
        }
        if (res.matched) {
            if (holdStart < 0) holdStart = nowMs
            val needMs = kf.holdS * holdScale * 1000
            val frac = if (needMs <= 0) 1f else ((nowMs - holdStart) / needMs).toFloat().coerceIn(0f, 1f)
            if (frac >= 1f) {
                advance(idx + 1, PoseResult.MATCHED, nowMs)
                return state(if (idx >= keyframes.size) V2Status.COMPLETE else V2Status.ADJUST, res.score)
            }
            return state(V2Status.HOLDING, res.score, hold = frac)
        }
        holdStart = -1
        return state(V2Status.ADJUST, res.score, selectCues(kf, live, res))
    }
}
