package com.example.poseengine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlin.math.roundToInt

/**
 * Live Tai Chi coach (Android port of coach_v2.py): MediaPipe Pose Landmarker -> 8 joint angles ->
 * per-joint scoring against the current reference_v2 keyframe -> expert or generated corrections.
 *
 * Call analyze() from one background thread (CameraX's analysis executor). The model and reference are
 * loaded on the first frame, so constructing the engine is cheap. All detector code, the model and the
 * reference live in this module; the UI only renders the returned [PoseFeedback].
 *
 * @param tolScale multiplies every joint tolerance, as coach_v2.py --tol-scale (>1 is more forgiving)
 * @param holdScale multiplies every keyframe's hold time, as coach_v2.py --hold-scale
 */
class MediaPipePoseFeedbackEngine(
    context: Context,
    private val modelAsset: String = "pose_landmarker_lite.task",
    private val referenceDir: String = "reference_v2",
    private val tolScale: Double = 1.0,
    private val holdScale: Double = 1.0,
) : PoseFeedbackEngine {

    private val appContext = context.applicationContext
    private var landmarker: PoseLandmarker? = null
    private var reference: ReferenceV2? = null
    private var coach: TaiChiCoachV2? = null
    private var initError: String? = null
    private var firstFrameNs = -1L
    private var lastTimestampMs = -1L
    private var lastFrame: PoseFrame? = null

    override fun lastFrame(): PoseFrame? = lastFrame

    // Controls may be pressed before the first frame arrives; apply them once the coach exists.
    @Volatile private var pendingCommand: Int = 0   // 1 restart, 2 next, 3 previous

    override fun restart() { coach?.restart() ?: run { pendingCommand = 1 } }
    override fun next() { coach?.next() ?: run { pendingCommand = 2 } }
    override fun previous() { coach?.previous() ?: run { pendingCommand = 3 } }

    override fun currentReference(): ReferenceImage? {
        val ref = reference ?: return null
        val c = coach ?: return null
        val total = c.total
        if (total == 0) return null
        val i = minOf(c.index, total - 1)   // after the last pose, keep showing the last one
        val bmp = ref.thumbnail(i) ?: return null
        return ReferenceImage("Pose ${i + 1}/$total · ${c.keyframe(i).poseName}", bmp)
    }

    private fun ensureInitialised(): Boolean {
        if (landmarker != null && coach != null) return true
        if (initError != null) return false
        try {
            val ref = ReferenceV2.load(appContext, referenceDir)
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAsset).build())
                .setRunningMode(RunningMode.VIDEO)   // synchronous detectForVideo(); we already run off the main thread
                .setNumPoses(1)
                .build()
            landmarker = PoseLandmarker.createFromOptions(appContext, options)
            reference = ref
            coach = TaiChiCoachV2(ref.keyframes, tolScale, holdScale).also {
                when (pendingCommand) { 1 -> it.restart(); 2 -> it.next(); 3 -> it.previous() }
                pendingCommand = 0
            }
            return true
        } catch (t: Throwable) {   // includes UnsatisfiedLinkError from the native library
            initError = t.message ?: t.javaClass.simpleName
            return false
        }
    }

    override fun analyze(frame: ImageProxy): List<PoseFeedback> {
        if (!ensureInitialised()) {
            return listOf(PoseFeedback("Pose engine failed to start", initError ?: "unknown error", Severity.WARNING))
        }
        val lm = landmarker!!
        val activeCoach = coach!!

        // Frame time: MediaPipe's VIDEO mode needs strictly increasing timestamps.
        val frameNs = frame.imageInfo.timestamp
        if (firstFrameNs < 0) firstFrameNs = frameNs
        var tsMs = (frameNs - firstFrameNs) / 1_000_000
        if (tsMs <= lastTimestampMs) tsMs = lastTimestampMs + 1
        lastTimestampMs = tsMs

        // Upright, NOT mirrored: the angles need true left/right (the preview mirrors itself for the user).
        val upright = uprightBitmap(frame)
        val result = lm.detectForVideo(BitmapImageBuilder(upright).build(), tsMs)

        var angles: DoubleArray? = null
        var xyz: FloatArray? = null
        var xy: FloatArray? = null
        var vis: FloatArray? = null
        if (result.landmarks().isNotEmpty() && result.worldLandmarks().isNotEmpty()) {
            val image = result.landmarks()[0]
            val world = result.worldLandmarks()[0]
            if (image.size >= LANDMARK_COUNT && world.size >= LANDMARK_COUNT) {
                xyz = FloatArray(LANDMARK_COUNT * 3)
                xy = FloatArray(LANDMARK_COUNT * 2)
                vis = FloatArray(LANDMARK_COUNT)
                for (i in 0 until LANDMARK_COUNT) {
                    xyz[i * 3] = world[i].x(); xyz[i * 3 + 1] = world[i].y(); xyz[i * 3 + 2] = world[i].z()
                    xy[i * 2] = image[i].x(); xy[i * 2 + 1] = image[i].y()
                    vis[i] = image[i].visibility().orElse(0f)
                }
                angles = computeJointAngles(xyz, vis)
            }
        }
        val poseIndex = activeCoach.index   // the pose being compared in this frame, before update() may advance it
        val state = activeCoach.update(angles, tsMs)
        lastFrame = PoseFrame(frameNs, poseIndex, activeCoach.total, angles, xyz, xy, vis)
        return toFeedback(state, activeCoach)
    }

    private fun uprightBitmap(frame: ImageProxy): Bitmap {
        val raw = frame.toBitmap()   // ARGB copy; the app asks CameraX for RGBA_8888 so this is a plain copy
        val rotation = frame.imageInfo.rotationDegrees
        if (rotation == 0) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }

    private fun toFeedback(s: V2State, coach: TaiChiCoachV2): List<PoseFeedback> {
        val out = ArrayList<PoseFeedback>(6)
        if (s.banner != null) out += PoseFeedback("Matched!", s.banner, Severity.GOOD)

        if (s.status == V2Status.COMPLETE) {
            out += PoseFeedback("Sequence complete!",
                "Average match on matched poses: ${s.averageMatched.roundToInt()}%. Press Restart to practise again.",
                Severity.GOOD)
            return out
        }

        val kf = coach.keyframe(s.index)
        val title = "Pose ${s.index + 1}/${s.total} · Movement ${kf.movementNo} of ${coach.movementCount}"
        val thumb = reference?.thumbnail(s.index)
        val score = if (s.score.isNaN()) "" else "Match ${s.score.roundToInt()}%"
        when (s.status) {
            V2Status.NO_PERSON ->
                out += PoseFeedback(title, "Step into view so your whole body is visible.", Severity.WARNING, thumb)
            V2Status.CANT_SEE ->
                out += PoseFeedback(title, "I can't see your ${s.unseen.joinToString(", ")}. Step back or adjust the camera.",
                    Severity.WARNING, thumb)
            V2Status.HOLDING ->
                out += PoseFeedback(title, "$score - hold it... (${(s.holdFraction * 100).roundToInt()}%)", Severity.GOOD, thumb)
            V2Status.ADJUST -> {
                out += PoseFeedback(title, "$score (need 70%)".takeIf { score.isNotEmpty() } ?: "Match the pose shown.",
                    Severity.INFO, thumb)
                for (cue in s.cues) out += PoseFeedback(if (cue.expert) "Coach tip" else "Adjust", cue.text, Severity.TIP)
            }
            V2Status.COMPLETE -> Unit
        }

        // Expert guidance for the current pose (INFO, so it stays in the side list, not the big banner).
        out += PoseFeedback(kf.poseName.ifEmpty { kf.id }, kf.instruction.ifEmpty { "Copy the pose shown." }, Severity.INFO)
        if (kf.keyPoints.isNotEmpty()) out += PoseFeedback("Key points", kf.keyPoints, Severity.INFO)
        if (kf.mistakes.isNotEmpty()) out += PoseFeedback("Common mistakes", kf.mistakes, Severity.INFO)
        return out
    }

    override fun close() {
        landmarker?.close()
        landmarker = null
    }
}
