package com.example.poseengine

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy

/**
 * Stable boundary between the camera/UI app and the replaceable pose detector.
 *
 * Implementations should consume the camera frame synchronously or copy its data
 * before returning. The caller owns ImageProxy and closes it after analyze() returns.
 * Keep model files and detector-specific dependencies inside this module so the
 * engine can be replaced/upgraded without changing the UI.
 */
interface PoseFeedbackEngine {
    fun analyze(frame: ImageProxy): List<PoseFeedback>
    fun close() {}

    /** Practice-sequence controls. Engines without a sequence can ignore them. */
    fun restart() {}
    fun next() {}
    fun previous() {}

    /**
     * The pose the user is currently asked to copy, independent of what this frame's feedback says
     * (so it is available even when no person is detected). Null if the engine has no reference.
     */
    fun currentReference(): ReferenceImage? = null
}

data class ReferenceImage(val label: String, val bitmap: Bitmap)

data class PoseFeedback(
    val title: String,
    val detail: String,
    val severity: Severity = Severity.INFO,
    /** Optional picture shown on the card (e.g. the pose to copy). Engines keep the instance stable between frames. */
    val thumbnail: Bitmap? = null
)

enum class Severity { INFO, GOOD, TIP, WARNING }

/**
 * Deliberately does not invent form corrections. Replace this implementation with
 * an adapter around the actual Tai-Chi pose detector.
 */
class UnconfiguredPoseFeedbackEngine : PoseFeedbackEngine {
    override fun analyze(frame: ImageProxy): List<PoseFeedback> = listOf(
        PoseFeedback(
            title = "Pose engine not connected",
            detail = "Connect the Tai-Chi detector in the pose-engine module to enable live form feedback."
        )
    )
}