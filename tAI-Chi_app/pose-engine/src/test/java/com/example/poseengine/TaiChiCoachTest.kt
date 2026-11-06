package com.example.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaiChiCoachTest {
    private val poseA = doubleArrayOf(140.0, 140.0, 25.0, 25.0, 175.0, 170.0, 160.0, 170.0)
    private val poseB = doubleArrayOf(100.0, 130.0, 70.0, 55.0, 140.0, 160.0, 155.0, 157.0)
    private fun coach() = TaiChiCoach(listOf(poseA, poseB), threshold = 20.0, holdMs = 600, flashMs = 1000)

    @Test fun holdingThenAdvancing() {
        val c = coach()
        val s0 = c.update(poseA, 0)
        assertEquals(CoachStatus.HOLDING, s0.status)
        assertEquals(0, s0.index)
        val s1 = c.update(poseA, 300)
        assertEquals(CoachStatus.HOLDING, s1.status)
        assertEquals(0.5f, s1.holdFraction, 1e-3f)
        val s2 = c.update(poseA, 650)
        assertEquals(1, s2.index)
        assertTrue(s2.flashing)
    }

    @Test fun wrongPoseGivesCuesForWorstJoints() {
        val c = coach()
        val live = poseA.copyOf().also { it[0] = 60.0 }          // left elbow far too bent
        val s = c.update(live, 0)
        assertEquals(CoachStatus.ADJUST, s.status)
        assertEquals("straighten your left elbow", s.cues.first())
    }

    @Test fun cueDirections() {
        assertEquals("bend your right knee", cueFor("R_knee", 30.0))
        assertEquals("straighten your left hip", cueFor("L_hip", -30.0))
        assertEquals("lower your left arm", cueFor("L_shoulder", 10.0))
        assertEquals("raise your right arm", cueFor("R_shoulder", -10.0))
    }

    @Test fun holdResetsWhenPoseIsLost() {
        val c = coach()
        c.update(poseA, 0)
        c.update(poseB, 200)                                    // wrong pose: hold cancelled
        val s = c.update(poseA, 700)
        assertEquals(CoachStatus.HOLDING, s.status)             // restarted at 700, not advanced
        assertEquals(0, s.index)
    }

    @Test fun noPersonAndTooFewJoints() {
        val c = coach()
        assertEquals(CoachStatus.NO_PERSON, c.update(null, 0).status)
        val sparse = DoubleArray(8) { Double.NaN }.also { it[0] = 140.0; it[1] = 140.0 }
        assertEquals(CoachStatus.TOO_FEW_JOINTS, c.update(sparse, 10).status)
    }

    @Test fun sequenceCompletesAndControlsWork() {
        val c = coach()
        c.update(poseA, 0); c.update(poseA, 700)
        c.update(poseB, 800); val done = c.update(poseB, 1500)
        assertEquals(CoachStatus.COMPLETE, done.status)
        assertEquals(CoachStatus.COMPLETE, c.update(poseB, 1600).status)
        c.restart()
        assertEquals(0, c.index)
        c.next(); assertEquals(1, c.index)
        c.previous(); assertEquals(0, c.index)
        c.previous(); assertEquals(0, c.index)
        assertFalse(c.update(poseA, 5000).flashing)
    }
}
