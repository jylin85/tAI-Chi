package com.example.poseengine

import kotlin.math.acos
import kotlin.math.sqrt

/*
 * Port of pose_common.py: the 8 joint angles the coach compares.
 * Pure Kotlin (no Android or MediaPipe types) so it can be unit-tested on the JVM.
 *
 * ORDER MATTERS: it must match "joint_names" in reference/reference.json.
 */
internal val JOINT_NAMES = listOf(
    "L_elbow", "R_elbow", "L_shoulder", "R_shoulder", "L_hip", "R_hip", "L_knee", "R_knee"
)

/** BlazePose landmark indices (point A, vertex B, point C) for each joint, same order as [JOINT_NAMES]. */
private val JOINT_POINTS = arrayOf(
    intArrayOf(11, 13, 15), intArrayOf(12, 14, 16),   // elbows
    intArrayOf(13, 11, 23), intArrayOf(14, 12, 24),   // shoulders
    intArrayOf(11, 23, 25), intArrayOf(12, 24, 26),   // hips
    intArrayOf(23, 25, 27), intArrayOf(24, 26, 28),   // knees
)

internal const val LANDMARK_COUNT = 33

/** Angle at vertex b formed by a-b-c, in degrees (3D). [p] holds x,y,z per landmark. */
internal fun angleDeg(p: FloatArray, a: Int, b: Int, c: Int): Double {
    val bax = (p[a * 3] - p[b * 3]).toDouble()
    val bay = (p[a * 3 + 1] - p[b * 3 + 1]).toDouble()
    val baz = (p[a * 3 + 2] - p[b * 3 + 2]).toDouble()
    val bcx = (p[c * 3] - p[b * 3]).toDouble()
    val bcy = (p[c * 3 + 1] - p[b * 3 + 1]).toDouble()
    val bcz = (p[c * 3 + 2] - p[b * 3 + 2]).toDouble()
    val dot = bax * bcx + bay * bcy + baz * bcz
    val norms = sqrt(bax * bax + bay * bay + baz * baz) * sqrt(bcx * bcx + bcy * bcy + bcz * bcz)
    val cos = (dot / (norms + 1e-9)).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(cos))
}

/**
 * 8 joint angles in degrees from the 3D world landmarks. A joint is NaN when any of its three
 * landmarks has visibility below [minVis].
 *
 * @param world 33 landmarks x (x, y, z), flattened (metres, hip-centred)
 * @param visibility 33 visibility scores from the image landmarks
 */
internal fun computeJointAngles(world: FloatArray, visibility: FloatArray, minVis: Float = 0.5f): DoubleArray {
    require(world.size == LANDMARK_COUNT * 3 && visibility.size == LANDMARK_COUNT) { "expected 33 landmarks" }
    return DoubleArray(JOINT_POINTS.size) { k ->
        val (a, b, c) = JOINT_POINTS[k].let { Triple(it[0], it[1], it[2]) }
        if (minOf(visibility[a], visibility[b], visibility[c]) >= minVis) angleDeg(world, a, b, c) else Double.NaN
    }
}
