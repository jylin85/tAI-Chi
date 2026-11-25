package com.example.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* Expected values are worked out from matching.py / coach_v2.py, so the app and the Python coach agree. */
class CoachV2Test {
    private val nan = Double.NaN

    // Arms critical (weight 1), legs secondary (0.5), like keyframe M02.1.
    private fun kf(
        id: String = "M02.1",
        target: DoubleArray = doubleArrayOf(144.0, 132.0, 73.0, 65.0, 167.0, 164.0, 161.0, 162.0),
        tol: DoubleArray = doubleArrayOf(18.0, 18.0, 12.0, 12.0, 16.0, 12.0, 12.0, 12.0),
        weight: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 0.5, 0.5, 0.5, 0.5),
        holdS: Double = 0.25,
        cues: Map<Pair<String, String>, String> = emptyMap(),
    ) = V2Keyframe(id, 2, "Beginning", "Hands up", holdS, "Raise your hands.", "Keep posture.", "Locking knees.",
        target, tol, weight, cues)

    @Test fun exactPoseScores100AndMatches() {
        val k = kf()
        val r = scorePose(k.target.copyOf(), k)
        assertEquals(100.0, r.score, 1e-9)
        assertTrue(r.matched)
        assertTrue(r.bad.isEmpty())
    }

    @Test fun edgeOfToleranceIs70AndStillMatches() {
        val k = kf()
        val live = k.target.copyOf().also { it[0] += 18.0 }          // z = 1 on a critical joint
        val r = scorePose(live, k)
        assertEquals(70.0, r.score, 1e-9)
        assertTrue(r.matched)
    }

    @Test fun secondaryJointGetsOneAndAHalfTimesTheTolerance() {
        val k = kf()
        val ok = k.target.copyOf().also { it[6] += 18.0 }            // knee: z = 1.5 = lenient limit
        assertTrue(scorePose(ok, k).matched)
        val tooFar = k.target.copyOf().also { it[6] += 24.0 }        // z = 2 > 1.5
        val r = scorePose(tooFar, k)
        assertFalse(r.matched)
        assertEquals(listOf(6), r.bad)
        // ze = 2 / 1.5 -> 70 * (3 - 1.333) / 2 = 58.33
        assertEquals(58.333, r.score, 1e-3)
    }

    @Test fun ignoredJointsAndNaNTargetsAreSkipped() {
        val k = kf(weight = doubleArrayOf(1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            target = doubleArrayOf(144.0, nan, 73.0, 65.0, 167.0, 164.0, 161.0, 162.0))
        val live = k.target.copyOf().also { it[1] = 10.0; it[2] = 0.0 }   // both ignored
        val r = scorePose(live, k)
        assertTrue(r.matched)   // only 1 judged joint, so need = 1
        assertEquals(100.0, r.score, 1e-9)
    }

    @Test fun unseenCriticalJointBlocksTheMatch() {
        val k = kf()
        val live = k.target.copyOf().also { it[0] = nan }
        val r = scorePose(live, k)
        assertEquals(listOf(0), r.unseen)
        assertFalse(r.matched)
        val secondaryHidden = k.target.copyOf().also { it[7] = nan }  // unseen secondary joint is fine
        assertTrue(scorePose(secondaryHidden, k).matched)
    }

    @Test fun tolScaleMakesItMoreForgiving() {
        val k = kf()
        val live = k.target.copyOf().also { it[0] += 25.0 }          // z = 1.39 at scale 1
        assertFalse(scorePose(live, k).matched)
        assertTrue(scorePose(live, k, tolScale = 1.5).matched)       // z = 0.93
    }

    @Test fun problemKeysAndAutoCues() {
        assertEquals("too_straight", problemKey("L_knee", 10.0))
        assertEquals("too_bent", problemKey("R_elbow", -10.0))
        assertEquals("arm_too_high", problemKey("L_shoulder", 5.0))
        assertEquals("arm_too_low", problemKey("R_shoulder", -5.0))
        assertEquals("Bend your left knee more (30° off)", autoCue("L_knee", 30.0))
        assertEquals("Raise your right arm (12° off)", autoCue("R_shoulder", -12.0))
    }

    @Test fun cuesPreferExpertThenAnyThenAuto_criticalFirst() {
        val k = kf(cues = mapOf(
            ("R_elbow" to "too_bent") to "Extend through the right fingertips",
            ("L_knee" to "any") to "Soften the left knee",
        ))
        val live = k.target.copyOf().also {
            it[1] -= 40.0     // R_elbow too bent (critical, z = 2.2)
            it[6] += 60.0     // L_knee too straight (secondary, z = 5)
            it[0] += 30.0     // L_elbow too straight (critical, z = 1.67)
        }
        val cues = selectCues(k, live, scorePose(live, k))
        assertEquals(2, cues.size)
        assertEquals(Cue("Extend through the right fingertips", true), cues[0])   // critical, worst
        assertEquals(Cue("Bend your left elbow more (30° off)", false), cues[1])  // critical, next
        val onlyKnee = k.target.copyOf().also { it[6] += 60.0 }
        assertEquals(Cue("Soften the left knee", true), selectCues(k, onlyKnee, scorePose(onlyKnee, k)).single())
    }

    @Test fun coachHoldsForThePosesOwnHoldTimeThenAdvancesWithBanner() {
        val a = kf("A", holdS = 0.25)
        val b = kf("B", holdS = 0.6)
        val c = TaiChiCoachV2(listOf(a, b))
        assertEquals(V2Status.HOLDING, c.update(a.target, 0).status)
        assertEquals(0.4f, c.update(a.target, 100).holdFraction, 1e-3f)
        val s = c.update(a.target, 260)
        assertEquals(1, s.index)
        assertEquals("Matched!  Next: Beginning Hands up", s.banner)
        assertEquals(V2Status.HOLDING, c.update(b.target, 300).status)
        assertEquals(1, c.update(b.target, 800).index)                // 0.5 s < 0.6 s
        val done = c.update(b.target, 910)
        assertEquals(V2Status.COMPLETE, done.status)
        assertEquals("Sequence complete - well done!", done.banner)
        assertEquals(100.0, done.averageMatched, 1e-9)
        assertNull(c.update(b.target, 5000).banner)                   // banner gone after 4 s
    }

    @Test fun losingThePoseResetsTheHold() {
        val a = kf("A", holdS = 0.6)
        val c = TaiChiCoachV2(listOf(a, kf("B")))
        c.update(a.target, 0)
        c.update(a.target.copyOf().also { it[0] += 40.0 }, 300)       // breaks the match
        val s = c.update(a.target, 700)
        assertEquals(V2Status.HOLDING, s.status)
        assertEquals(0, s.index)
    }

    @Test fun noPersonCantSeeAndControls() {
        val a = kf("A")
        val c = TaiChiCoachV2(listOf(a, kf("B")))
        assertEquals(V2Status.NO_PERSON, c.update(null, 0).status)
        val hidden = a.target.copyOf().also { it[0] = nan; it[3] = nan }
        val s = c.update(hidden, 10)
        assertEquals(V2Status.CANT_SEE, s.status)
        assertEquals(listOf("left elbow", "right shoulder"), s.unseen)
        c.next(); assertEquals(1, c.index)
        assertEquals(TaiChiCoachV2.PoseResult.SKIPPED, c.result(0))
        c.previous(); assertEquals(0, c.index)
        c.previous(); assertEquals(0, c.index)
        c.next(); c.next(); assertEquals(V2Status.COMPLETE, c.update(a.target, 20).status)
        c.next(); assertEquals(2, c.index)                            // stays complete
        c.restart(); assertEquals(0, c.index)
        assertEquals(TaiChiCoachV2.PoseResult.NOT_REACHED, c.result(0))
    }
}
