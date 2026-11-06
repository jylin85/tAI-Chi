package com.example.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JointAnglesTest {
    private fun landmarks(vararg pts: Pair<Int, Triple<Float, Float, Float>>): FloatArray {
        val a = FloatArray(LANDMARK_COUNT * 3)
        for ((i, p) in pts) { a[i * 3] = p.first; a[i * 3 + 1] = p.second; a[i * 3 + 2] = p.third }
        return a
    }

    @Test fun rightAngleAtElbow() {
        // L_elbow = angle at landmark 13 between 11 and 15
        val w = landmarks(11 to Triple(0f, 0f, 0f), 13 to Triple(1f, 0f, 0f), 15 to Triple(1f, 1f, 0f))
        assertEquals(90.0, angleDeg(w, 11, 13, 15), 1e-3)
    }

    @Test fun straightArmIs180() {
        val w = landmarks(11 to Triple(0f, 0f, 0f), 13 to Triple(1f, 0f, 0f), 15 to Triple(2f, 0f, 0f))
        assertEquals(180.0, angleDeg(w, 11, 13, 15), 1e-3)
    }

    @Test fun lowVisibilityGivesNaN() {
        val w = landmarks(11 to Triple(0f, 0f, 0f), 13 to Triple(1f, 0f, 0f), 15 to Triple(1f, 1f, 0f))
        val vis = FloatArray(LANDMARK_COUNT) { 1f }
        vis[15] = 0.2f
        val angles = computeJointAngles(w, vis)
        assertEquals(JOINT_NAMES.size, angles.size)
        assertTrue(angles[0].isNaN())           // L_elbow uses landmark 15
        assertTrue(!angles[2].isNaN())          // L_shoulder (13, 11, 23) does not
    }
}
