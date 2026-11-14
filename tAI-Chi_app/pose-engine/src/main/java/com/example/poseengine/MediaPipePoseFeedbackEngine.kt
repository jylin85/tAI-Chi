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
 * Live Tai Chi coach (Android port of coach.py): MediaPipe Pose Landmarker -> 8 joint angles ->
 * compare with the next reference keyframe -> spoken-style corrections.
 *
 * Call analyze() from one background thread (CameraX's analysis executor). The model and reference are
 * loaded on the first frame, so constructing the engine is cheap. All detector code, the model and the
 * reference live in this module; the UI only renders the returned [PoseFeedback].
 *
 * @param threshold max mean joint-angle error in degrees that counts as a match (coach.py default 20)
 * @param holdSeconds how long a pose must be held (coach.py default 0.6)
 */
class MediaPipePoseFeedbackEngine(
    context: Context,
    private val modelAsset: String = "pose_landmarker_lite.task",
    private val referenceDir: String = "reference",
    private val threshold: Double = 20.0,
    private val holdSeconds: Double = 0.6,
) : PoseFeedbackEngine {

    private val appContext = context.applicationContext
    private var landmarker: PoseLandmarker? = null
    private var reference: ReferenceSequence? = null
    private var coach: TaiChiCoach? = null
    private var initError: String? = null
    private var firstFrameNs = -1L
    private var lastTimestampMs = -1L

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
        return ReferenceImage("Pose ${i + 1}/$total", bmp)
    }

    private fun ensureInitialised(): Boolean {
        if (landmarker != null && coach != null) return true
        if (initError != null) return false
        try {
            val ref = ReferenceSequence.load(appContext, referenceDir)
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAsset).build())
                .setRunningMode(RunningMode.VIDEO)   // synchronous detectForVideo(); we already run off the main thread
                .setNumPoses(1)
                .build()
            landmarker = PoseLandmarker.createFromOptions(appContext, options)
            reference = ref
            coach = TaiChiCoach(ref.targets, threshold, (holdSeconds * 1000).toLong()).also {
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
        if (result.landmarks().isNotEmpty() && result.worldLandmarks().isNotEmpty()) {
            val image = result.landmarks()[0]
            val world = result.worldLandmarks()[0]
            if (image.size >= LANDMARK_COUNT && world.size >= LANDMARK_COUNT) {
                val xyz = FloatArray(LANDMARK_COUNT * 3)
                val vis = FloatArray(LANDMARK_COUNT)
                for (i in 0 until LANDMARK_COUNT) {
                    xyz[i * 3] = world[i].x(); xyz[i * 3 + 1] = world[i].y(); xyz[i * 3 + 2] = world[i].z()
                    vis[i] = image[i].visibility().orElse(0f)
                }
                angles = computeJointAngles(xyz, vis)
            }
        }
        return toFeedback(activeCoach.update(angles, tsMs), activeCoach)
    }

    private fun uprightBitmap(frame: ImageProxy): Bitmap {
        val raw = frame.toBitmap()   // ARGB copy; the app asks CameraX for RGBA_8888 so this is a plain copy
        val rotation = frame.imageInfo.rotationDegrees
        if (rotation == 0) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }

    private fun toFeedback(s: CoachState, coach: TaiChiCoach): List<PoseFeedback> {
        val out = ArrayList<PoseFeedback>(4)
        if (s.flashing) out += PoseFeedback("Pose matched!", "Nicely done.", Severity.GOOD)

        val title = if (s.status == CoachStatus.COMPLETE) "Pose ${s.total}/${s.total}" else "Pose ${s.index + 1}/${s.total}"
        val thumb = if (s.index < s.total) reference?.thumbnail(s.index) else null
        val limit = coach.threshold.roundToInt()
        when (s.status) {
            CoachStatus.COMPLETE ->
                out += PoseFeedback("Sequence complete!", "Press Restart to practise again.", Severity.GOOD)
            CoachStatus.NO_PERSON ->
                out += PoseFeedback(title, "No person detected. Step back so your whole body is visible.", Severity.WARNING, thumb)
            CoachStatus.TOO_FEW_JOINTS ->
                out += PoseFeedback(title, "Can't see enough joints. Step back or improve the lighting.", Severity.WARNING, thumb)
            CoachStatus.HOLDING ->
                out += PoseFeedback(title, "Good - hold it... (${(s.holdFraction * 100).roundToInt()}%)  Error ${s.meanError.roundToInt()}°", Severity.GOOD, thumb)
            CoachStatus.ADJUST -> {
                val err = if (s.meanError.isNaN()) "" else "Error ${s.meanError.roundToInt()}° (need < $limit°)"
                out += PoseFeedback(title, err.ifEmpty { "Match the pose shown." }, Severity.INFO, thumb)
                for (cue in s.cues) out += PoseFeedback("Adjust", cue.replaceFirstChar { it.uppercase() }, Severity.TIP)
            }
        }
        return out
    }

    override fun close() {
        landmarker?.close()
        landmarker = null
    }
}